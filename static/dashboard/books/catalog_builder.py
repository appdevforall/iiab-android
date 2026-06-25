import sqlite3
import tarfile
import xml.etree.ElementTree as ET
import os
import time
import urllib.request
import argparse
import sys
import gzip
import hashlib

# Project Gutenberg XML Namespaces
NAMESPACES = {
    'rdf': 'http://www.w3.org/1999/02/22-rdf-syntax-ns#',
    'dcterms': 'http://purl.org/dc/terms/',
    'pgterms': 'http://www.gutenberg.org/2009/pgterms/'
}

CATALOG_URL = 'https://gutenberg.org/cache/epub/feeds/rdf-files.tar.bz2'
DEFAULT_TAR_FILE = 'rdf-files.tar.bz2'
OUTPUT_DB_NAME = 'catalog.db'

def ensure_tar_file(custom_path=None):
    """
    Determines which file to process.
    Downloads the catalog if it doesn't exist locally.
    """
    if custom_path:
        if not os.path.exists(custom_path):
            print(f"- Error: The provided file does not exist: {custom_path}")
            sys.exit(1)
        print(f"- Using provided local file: {custom_path}")
        return custom_path

    if os.path.exists(DEFAULT_TAR_FILE):
        print(f"- Default file found locally: {DEFAULT_TAR_FILE}. Skipping download.")
        return DEFAULT_TAR_FILE

    print(f"- Downloading catalog from Gutenberg (approx 30MB) to {DEFAULT_TAR_FILE}...")
    req = urllib.request.Request(
        CATALOG_URL,
        headers={'User-Agent': 'K2Go Catalog-Builder/1.0 (https://github.com/appdevforall/KnowledgeToGo)'}
    )

    try:
        with urllib.request.urlopen(req) as response:
            with open(DEFAULT_TAR_FILE, 'wb') as out_file:
                chunk_size = 1024 * 1024
                while True:
                    chunk = response.read(chunk_size)
                    if not chunk:
                        break
                    out_file.write(chunk)
        print("- Download completed successfully!")
        return DEFAULT_TAR_FILE
    except Exception as e:
        print(f"- Failed to download the catalog: {e}")
        sys.exit(1)


def extract_book_metadata(root):
    """Extracts metadata, the exact EPUB URL, download count, bookshelves, and description."""
    ebook_node = root.find('.//pgterms:ebook', NAMESPACES)
    if ebook_node is None:
        return None

    about_attr = ebook_node.get(f"{{{NAMESPACES['rdf']}}}about")
    gutenberg_id = about_attr.replace('ebooks/', '') if about_attr else None

    if not gutenberg_id:
        return None

    title_node = root.find('.//dcterms:title', NAMESPACES)
    title = title_node.text if title_node is not None else 'Unknown Title'

    author_node = root.find('.//dcterms:creator//pgterms:name', NAMESPACES)
    author = author_node.text if author_node is not None else 'Unknown Author'

    lang_node = root.find('.//dcterms:language//rdf:value', NAMESPACES)
    language = lang_node.text if lang_node is not None else 'en'

    desc_node = root.find('.//dcterms:description', NAMESPACES)
    description = desc_node.text.strip() if desc_node is not None and desc_node.text else 'No description available for this book.'

    downloads_node = root.find('.//pgterms:downloads', NAMESPACES)
    downloads = 0
    if downloads_node is not None and downloads_node.text and downloads_node.text.isdigit():
        downloads = int(downloads_node.text)

    bookshelves = []
    for shelf_node in root.findall('.//pgterms:bookshelf//rdf:value', NAMESPACES):
        if shelf_node.text:
            bookshelves.append(shelf_node.text)
    bookshelves_str = ", ".join(bookshelves)

    epub_url = None
    available_files = []

    for file_node in root.findall('.//pgterms:file', NAMESPACES):
        file_url = file_node.get(f"{{{NAMESPACES['rdf']}}}about")
        if file_url:
            available_files.append(file_url)

    priorities = ['.epub3.images', '.epub.images', '.epub3.noimages', '.epub.noimages']

    for ext in priorities:
        match = next((f for f in available_files if f.endswith(ext)), None)
        if match:
            epub_url = match
            break

    return (gutenberg_id, title, author, language, epub_url, downloads, bookshelves_str, description)


def build_database(target_tar_file, output_dir):
    start_time = time.time()
    print("- Starting in-memory database build...")

    mem_db = sqlite3.connect(':memory:')
    cursor = mem_db.cursor()

    cursor.execute('''
        CREATE TABLE temp_catalog (
            gutenberg_id TEXT, title TEXT, author TEXT,
            language TEXT, download_url TEXT,
            downloads INTEGER, bookshelves TEXT,
            description TEXT
        )
    ''')

    insert_query = '''
        INSERT INTO temp_catalog (gutenberg_id, title, author, language, download_url, downloads, bookshelves, description)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    '''

    books_processed = 0

    print(f"- Streaming and parsing {target_tar_file}...")
    mode = 'r:bz2' if target_tar_file.endswith('.bz2') else 'r:gz' if target_tar_file.endswith('.gz') else 'r'

    with tarfile.open(target_tar_file, mode) as tar:
        for member in tar:
            if member.isreg() and member.name.endswith('.rdf'):
                xml_file = tar.extractfile(member)
                if xml_file is None:
                    continue

                try:
                    tree = ET.parse(xml_file)
                    metadata = extract_book_metadata(tree.getroot())

                    if metadata and metadata[0] and metadata[4]:
                        cursor.execute(insert_query, metadata)
                        books_processed += 1

                        if books_processed % 10000 == 0:
                            print(f"   Processed {books_processed} books with EPUBs...")

                except ET.ParseError:
                    pass

    print("- All books loaded into RAM. Applying strict 1,100 books filter...")

    # Se añade description al índice de búsqueda FTS5
    cursor.execute('''
        CREATE VIRTUAL TABLE catalog USING fts5(
            gutenberg_id UNINDEXED,
            title,
            author,
            language,
            download_url UNINDEXED,
            bookshelves,
            description,
            downloads UNINDEXED
        )
    ''')

    cursor.execute('''
        INSERT INTO catalog (gutenberg_id, title, author, language, download_url, bookshelves, description, downloads)
        SELECT gutenberg_id, title, author, language, download_url, bookshelves, description, downloads
        FROM (
            -- Group A: Top 100 most downloaded books overall
            SELECT * FROM (
                SELECT * FROM temp_catalog
                ORDER BY downloads DESC
                LIMIT 100
            )

            UNION ALL

            -- Group B: Top 1000 Educational/Children books (Excluding the ones already in Group A)
            SELECT * FROM (
                SELECT * FROM temp_catalog
                WHERE (bookshelves LIKE '%Children%'
                   OR bookshelves LIKE '%Education%'
                   OR bookshelves LIKE '%School%'
                   OR bookshelves LIKE '%Instructional%')
                  AND gutenberg_id NOT IN (
                      SELECT gutenberg_id FROM temp_catalog ORDER BY downloads DESC LIMIT 100
                  )
                ORDER BY downloads DESC
                LIMIT 1000
            )
        )
    ''')

    cursor.execute('DROP TABLE temp_catalog')
    mem_db.commit()

    # ---------------------------------------------------------
    # OUTPUT AND COMPRESSION LOGIC
    # ---------------------------------------------------------

    # Ensure the target directory exists
    os.makedirs(output_dir, exist_ok=True)

    final_db_path = os.path.join(output_dir, OUTPUT_DB_NAME)
    compressed_path = final_db_path + '.gz'
    hash_path = compressed_path + '.sha256'

    print(f"- Saving raw database to disk ({final_db_path})...")
    if os.path.exists(final_db_path):
        os.remove(final_db_path)

    disk_db = sqlite3.connect(final_db_path)
    with disk_db:
        mem_db.backup(disk_db)

    disk_db.close()
    mem_db.close()

    print("- Compressing database and generating SHA256 checksum...")

    # Compress the SQLite file
    with open(final_db_path, 'rb') as f_in:
        with gzip.open(compressed_path, 'wb') as f_out:
            f_out.writelines(f_in)

    # Generate the SHA256 hash
    sha256_hash = hashlib.sha256()
    with open(compressed_path, "rb") as f:
        for byte_block in iter(lambda: f.read(4096), b""):
            sha256_hash.update(byte_block)

    hash_hex = sha256_hash.hexdigest()

    # Save the hash to a text file
    with open(hash_path, 'w') as f_out:
        f_out.write(hash_hex)

    elapsed = time.time() - start_time
    print(f"- Done! Curated library ready in {elapsed:.2f} seconds.")
    print(f"   - Output Directory: {os.path.abspath(output_dir)}")
    print(f"   - Uncompressed:     {OUTPUT_DB_NAME} ({os.path.getsize(final_db_path) // 1024} KB)")
    print(f"   - Compressed:       {os.path.basename(compressed_path)} ({os.path.getsize(compressed_path) // 1024} KB)")
    print(f"   - Checksum:         {os.path.basename(hash_path)} ({hash_hex[:8]}...)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Project Gutenberg Database Builder.")
    parser.add_argument("-f", "--file", type=str, help="Optional path to a local .tar.bz2 or .tar.gz file", default=None)
    parser.add_argument("-o", "--outdir", type=str, help="Output directory for the generated database files", default=".")

    args = parser.parse_args()

    target_file = ensure_tar_file(args.file)
    build_database(target_file, args.outdir)
