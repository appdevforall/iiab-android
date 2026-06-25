// sockets/books.socket.ts
import { Socket } from 'socket.io';
import Database from 'better-sqlite3';
import { exec } from 'child_process';
import util from 'util';
import fs from 'fs';
import path from 'path';
import { pipeline } from 'stream/promises';
import { createGunzip } from 'zlib';
import { Readable } from 'stream';

const execAsync = util.promisify(exec);

// --- PATH CONFIGURATION ---
const CALIBRE_LIB_PATH = '/library/calibre-web/';
const CALIBRE_DB_PATH = path.join(CALIBRE_LIB_PATH, 'metadata.db');
const BOOKS_DIR = '/library/dashboard/books/';
const CATALOG_DB_PATH = path.join(BOOKS_DIR, 'catalog.db');
const LOCAL_HASH_PATH = path.join(BOOKS_DIR, 'catalog.db.gz.sha256');
const TMP_DIR = '/tmp/books_downloader/';

// --- REMOTE CONFIGURATION (MASTER SERVER) ---
const REMOTE_BASE_URL = 'https://iiab.switnet.org/android/pg';
const SYSTEM_USER_AGENT = 'K2Go Dashboard/1.0 (https://github.com/appdevforall/KnowledgeToGo)';

// --- LOCAL CALIBRE-WEB CONFIGURATION ---
const CALIBRE_WEB_LOCAL_URL = 'http://127.0.0.1:8083';

// Variables for credentials so they can be updated from the UI
let CALIBRE_WEB_USER = 'Admin';
let CALIBRE_WEB_PASS = 'changeme';

// --- SECURE INITIALIZATION & JANITOR ROUTINE ---
if (!fs.existsSync(BOOKS_DIR)) {
    fs.mkdirSync(BOOKS_DIR, { recursive: true });
}

if (fs.existsSync(TMP_DIR)) {
    try {
        console.log('[Books] Cleaning up temporary directory on startup...');
        const orphanedFiles = fs.readdirSync(TMP_DIR);
        for (const file of orphanedFiles) {
            const filePath = path.join(TMP_DIR, file);
            if (fs.lstatSync(filePath).isFile()) {
                fs.unlinkSync(filePath);
            }
        }
    } catch (cleanupError) {
        console.error('[Books] Warning: Failed to clean TMP_DIR:', cleanupError);
    }
} else {
    fs.mkdirSync(TMP_DIR, { recursive: true });
}

// ==========================================
// CALIBRE-WEB SESSION MANAGER (CSRF & Cookies)
// ==========================================
async function getCalibreSession(socket: Socket) {
    console.log('[Books] Authenticating with Calibre-Web...');

    // 1. Visit the login page
    const loginPageRes = await fetch(`${CALIBRE_WEB_LOCAL_URL}/login`);

    const initialCookies = loginPageRes.headers.getSetCookie().map(c => c.split(';')[0]).join('; ');
    const loginHtml = await loginPageRes.text();

    const csrfMatch = loginHtml.match(/name="csrf_token" value="(.*?)"/);
    if (!csrfMatch) throw new Error('Could not find CSRF token on login page');
    const csrfToken = csrfMatch[1];

    // 2. Submit the login form
    const loginData = new URLSearchParams();
    loginData.append('csrf_token', csrfToken);
    loginData.append('username', CALIBRE_WEB_USER);
    loginData.append('password', CALIBRE_WEB_PASS);

    const authRes = await fetch(`${CALIBRE_WEB_LOCAL_URL}/login`, {
        method: 'POST',
        headers: {
            'Cookie': initialCookies,
            'Content-Type': 'application/x-www-form-urlencoded',
            'Referer': `${CALIBRE_WEB_LOCAL_URL}/login`
        },
        body: loginData,
        redirect: 'manual'
    });

    if (authRes.status !== 302 && authRes.status !== 303) {
        console.log('[Books] Authentication failed. Requesting new credentials from UI.');
        socket.emit('calibre_auth_required');
        throw new Error('Invalid Calibre-Web credentials');
    }

    const authCookiesArray = authRes.headers.getSetCookie();
    const authCookieString = authCookiesArray.map(c => c.split(';')[0]).join('; ');

    // 4. Visit home page to get a fresh CSRF token
    const homePageRes = await fetch(`${CALIBRE_WEB_LOCAL_URL}/`, {
        headers: { 'Cookie': authCookieString }
    });
    const homeHtml = await homePageRes.text();

    const finalCsrfMatch = homeHtml.match(/name="csrf_token"\s+value="([^"]+)"/i) ||
                           homeHtml.match(/value="([^"]+)"\s+name="csrf_token"/i) ||
                           homeHtml.match(/content="([^"]+)"\s+name="csrf[-_]token"/i);

    const finalCsrfToken = finalCsrfMatch ? finalCsrfMatch[1] : csrfToken;

    console.log('[Books] Authentication successful!');
    return { cookie: authCookieString, csrfToken: finalCsrfToken };
}

// ==========================================
// SYNC ENGINE
// ==========================================
async function syncCatalog(socket: Socket) {
    try {
        console.log('[Books] Checking for remote catalog updates...');
        socket.emit('books_sync_status', { status: 'checking', msg: 'Checking for catalog updates...' });

        // 1. Fetch remote hash
        const hashRes = await fetch(`${REMOTE_BASE_URL}/catalog.db.gz.sha256`, {
            headers: { 'User-Agent': SYSTEM_USER_AGENT },
            cache: 'no-store'
        });

        if (!hashRes.ok) throw new Error('Could not contact the catalog server.');
        const remoteHash = (await hashRes.text()).trim();

        // 2. Read local hash
        let localHash = '';
        if (fs.existsSync(LOCAL_HASH_PATH)) {
            localHash = fs.readFileSync(LOCAL_HASH_PATH, 'utf-8').trim();
        }

        const rHashTrunc = remoteHash.substring(0, 8);
        const lHashTrunc = localHash ? localHash.substring(0, 8) : 'NONE';
        console.log(`[Books] Remote Hash: ${rHashTrunc}... | Local Hash: ${lHashTrunc}...`);

        // 3. Compare and skip if identical
        if (remoteHash === localHash && fs.existsSync(CATALOG_DB_PATH)) {
            console.log('[Books] Catalog is already up to date. Skipping download.');
            socket.emit('books_sync_status', { status: 'uptodate', msg: 'Catalog is up to date.' });
            return true;
        }

        console.log('[Books] Downloading new catalog database (~170 KB)...');
        socket.emit('books_sync_status', { status: 'downloading', msg: 'Downloading new catalog (~170 KB)...' });

        // 4. Download and extract
        const dbRes = await fetch(`${REMOTE_BASE_URL}/catalog.db.gz`, {
            headers: { 'User-Agent': SYSTEM_USER_AGENT },
            cache: 'no-store'
        });

        if (!dbRes.ok) throw new Error('Failed to download the compressed catalog.');

        const tempDbPath = CATALOG_DB_PATH + '.tmp';

        await pipeline(
            Readable.fromWeb(dbRes.body as any),
            createGunzip(),
            fs.createWriteStream(tempDbPath)
        );

        // 5. Atomic replacement
        fs.renameSync(tempDbPath, CATALOG_DB_PATH);
        fs.writeFileSync(LOCAL_HASH_PATH, remoteHash);

        console.log('[Books] Catalog downloaded and extracted successfully!');
        socket.emit('books_sync_status', { status: 'success', msg: 'Catalog synchronized successfully!' });
        return true;

    } catch (error) {
        console.error('[Books Sync Error]', error);
        socket.emit('books_sync_status', { status: 'error', msg: 'Failed to sync catalog. Using local version if available.' });
        return false;
    }
}

// ==========================================
// SOCKET CONTROLLER
// ==========================================
export const handleBooksEvents = (socket: Socket) => {

    // --- CREDENTIAL UPDATE ---
    socket.on('update_calibre_credentials', (data: { user: string, pass: string }) => {
        console.log('[Books] Updating Calibre-Web credentials in memory...');
        CALIBRE_WEB_USER = data.user;
        CALIBRE_WEB_PASS = data.pass;
        socket.emit('calibre_auth_updated');
    });

    // --- CATALOG SYNC ---
    socket.on('trigger_catalog_sync', async () => {
        const success = await syncCatalog(socket);
        if (success) {
            socket.emit('books_ready_to_load');
        }
    });

    // --- LOCAL DB HANDLING ---
    socket.on('request_local_books', () => {
        try {
            if (!fs.existsSync(CALIBRE_DB_PATH)) {
                socket.emit('local_books_ready', []);
                return;
            }

            const db = new Database(CALIBRE_DB_PATH, { readonly: true });
            const stmt = db.prepare(`
                SELECT
                    books.id,
                    books.title,
                    strftime('%Y', books.pubdate) as year,
                    (SELECT name FROM authors
                     JOIN books_authors_link ON authors.id = books_authors_link.author
                     WHERE book = books.id LIMIT 1) as author
                FROM books
                WHERE EXISTS (
                    SELECT 1 FROM data
                    WHERE data.book = books.id AND data.format = 'EPUB'
                )
                ORDER BY books.id DESC
            `);
            const localBooks = stmt.all();
            db.close();

            socket.emit('local_books_ready', localBooks);
        } catch (error) {
            console.error('[Books] Calibre DB Error:', error);
            socket.emit('books_error', 'Failed to read local Calibre library.');
        }
    });

    socket.on('delete_local_book', async (bookId: number) => {
        try {
            socket.emit('local_book_status', { id: bookId, status: 'deleting' });
            console.log(`[Books] Requesting deletion of book ${bookId} via Calibre-Web API...`);

            // Pass the socket to get the session!
            const authSession = await getCalibreSession(socket);

            const deleteData = new URLSearchParams();
            deleteData.append('csrf_token', authSession.csrfToken);

            const deleteRes = await fetch(`${CALIBRE_WEB_LOCAL_URL}/delete/${bookId}`, {
                method: 'POST',
                headers: {
                    'Cookie': authSession.cookie,
                    'Content-Type': 'application/x-www-form-urlencoded',
                    'Referer': `${CALIBRE_WEB_LOCAL_URL}/`
                },
                body: deleteData
            });

            if (!deleteRes.ok) {
                throw new Error(`Calibre-Web rejected deletion: ${deleteRes.status} ${deleteRes.statusText}`);
            }

            console.log(`[Books] Book ${bookId} deleted successfully.`);
            socket.emit('local_book_status', { id: bookId, status: 'deleted' });
            socket.emit('refresh_local_books');

        } catch (error: any) {
            console.error('[Books] Delete Error:', error);
            socket.emit('local_book_status', { id: bookId, status: 'error', message: error.message || 'Unknown error' });
        }
    });

    // --- REMOTE CATALOG HANDLING ---
    socket.on('request_books', (params: { filter: string, query: string }) => {
        try {
            if (!fs.existsSync(CATALOG_DB_PATH)) {
                socket.emit('books_error', 'Catalog database not found. Please synchronize first.');
                return;
            }

            const db = new Database(CATALOG_DB_PATH, { readonly: true });
            let books = [];

            if (params.query && params.query.trim().length > 0) {
                const stmt = db.prepare(`SELECT gutenberg_id, title, author, language, download_url, description FROM catalog WHERE catalog MATCH ? ORDER BY rank LIMIT 40`);
                books = stmt.all(params.query + '*');
            } else if (params.filter === 'educational') {
                const stmt = db.prepare(`SELECT gutenberg_id, title, author, language, download_url, description FROM catalog WHERE bookshelves LIKE '%Children%' OR bookshelves LIKE '%Education%' ORDER BY downloads DESC LIMIT 40`);
                books = stmt.all();
            } else {
                const stmt = db.prepare(`SELECT gutenberg_id, title, author, language, download_url, description FROM catalog ORDER BY downloads DESC LIMIT 40`);
                books = stmt.all();
            }

            db.close();
            socket.emit('books_list_ready', books);

        } catch (error) {
            console.error('[Books] DB Read Error:', error);
            socket.emit('books_error', 'Failed to read the database.');
        }
    });

    socket.on('download_books_batch', async (books: Array<{ id: string, title: string, url: string }>) => {
        let authSession;
        try {
            authSession = await getCalibreSession(socket);
        } catch (err) {
            console.log('[Books] Batch aborted due to auth failure. Waiting for UI credentials...');
            return;
        }

        for (const book of books) {
            const tempFilePath = path.join(TMP_DIR, `pg_${book.id}.epub`);

            try {
                console.log(`[Books] Batch downloading: ${book.title}`);
                socket.emit('book_status_update', { id: book.id, status: 'downloading' });

                // Gutenberg download
                const response = await fetch(book.url, {
                    headers: {
                        'User-Agent': SYSTEM_USER_AGENT,
                        'Accept': 'application/epub+zip'
                    }
                });

                if (!response.ok) throw new Error(`HTTP Error ${response.status} from Gutenberg`);

                const fileBuffer = await response.arrayBuffer();
                fs.writeFileSync(tempFilePath, Buffer.from(fileBuffer));

                socket.emit('book_status_update', { id: book.id, status: 'processing' });

                console.log(`[Books] Uploading ${book.title} to Calibre-Web...`);

                const fileBlob = new Blob([fileBuffer], { type: 'application/epub+zip' });
                const form = new FormData();
                form.append('csrf_token', authSession.csrfToken);
                form.append('btn-upload', fileBlob, `${book.title}.epub`);

                const uploadRes = await fetch(`${CALIBRE_WEB_LOCAL_URL}/upload`, {
                    method: 'POST',
                    headers: {
                        'Cookie': authSession.cookie,
                        'Referer': `${CALIBRE_WEB_LOCAL_URL}/`
                    },
                    body: form
                });

                if (!uploadRes.ok) {
                    throw new Error(`Calibre-Web rejected upload: ${uploadRes.status} ${uploadRes.statusText}`);
                }

                console.log(`[Books] Batch added successfully via API: ${book.title}`);
                socket.emit('book_status_update', { id: book.id, status: 'completed' });

            } catch (err: any) {
                console.error(`[Books] Error batch processing book ${book.id}:`, err);
                socket.emit('book_status_update', { id: book.id, status: 'error', message: err.message });
            } finally {
                if (fs.existsSync(tempFilePath)) {
                    fs.unlinkSync(tempFilePath);
                }
            }
        }

        socket.emit('refresh_local_books');
    });
};
