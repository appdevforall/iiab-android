# Knowledge To Go (K2Go)

> **Note:** This project is currently a **Proof of Concept (PoC)**. It demonstrates the feasibility of running, managing, and routing a localized "Internet in a Box" (IIAB) environment directly on an Android device.

## Overview

Knowledge To Go (K2Go) acts as the **Frontend system manager** for the educational ecosystem. It works in tandem with a **Termux backend**, bridging the gap between the native Android OS and the Linux subsystem where the actual IIAB services and modules reside. 

Instead of requiring users to type commands in a terminal, this app provides a clean, user-friendly graphical interface to deploy, monitor, and control the entire localized server stack.

## Key Features

* **System Dashboard:** Real-time monitoring of device resources including Storage, RAM, Virtual Swap, Battery, and others.
* **Termux Integration:** Automatically detects the Termux environment, aids managing permissions and serving as the primary bridge to control the backend server state.
* **Safe Pocket Web (VPN Tunneling):** Routes custom DNS proxy traffic using a native SOCKS5 tunnel. Allowing users to explore educational content (like Kiwix, Kolibri, etc.) seamlessly.
* **Embedded Content Browser:** A native, lightweight integrated WebView which allows users to access and interact with local educational platforms distraction-free.
* **Master Watchdog Service:** A background service designed to protect the Termux backend from Android's aggressive battery optimizations (Doze mode) and to keep Wi-Fi/Hotspot connections active.
* **Logging:** Built-in connection log manager to monitor watchdog activities.

## How to build

Because this project relies on external native C/C++ libraries via Git Submodules, a standard `git clone` is not enough.

To clone the repository and automatically fetch all required submodules, use:

```
git clone --recurse-submodules https://github.com/appdevforall/KnowledgeToGo
```

If you already cloned the repository without the --recurse-submodules flag and your build is failing, you can fetch the missing submodules by running the following command inside the project root:

```
git submodule update --init --recursive
```
After cloning, simply open the project in your IDE and sync the Gradle files. The NDK will automatically compile the native tunnel dependencies.

## Acknowledgments

This project is a heavily customized spin-off of **SocksTun**, created by [heiher](https://github.com/heiher).

All credit for the core native C/C++ tunneling engine goes to the original author. To ensure we receive upstream security patches and updates, the core engine (hev-socks5-tunnel) is linked directly as a Git submodule. The wrapper has been re-architected, stripped down, and integrated with a custom UI to meet the specific implementation needs of the Knowledge To Go project on Android.

## Disclaimer

**This software is a Proof of Concept (PoC) and is provided "as is", without warranty of any kind.** It is intended for research, testing, and educational development. Because it interacts heavily with Android's background services and networking stack, it may behave differently across various device manufacturers (OEMs). It is not yet guaranteed for stable, unattended production deployment. Use at your own risk.