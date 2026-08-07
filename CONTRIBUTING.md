# Contributing to HidBridge

First off, thank you for considering contributing to HidBridge! It's people like you that make this tool better for everyone.

## Code of Conduct

This project and everyone participating in it is governed by a friendly and inclusive community. We are committed to making participation in this project a harassment-free experience for everyone.

## How Can I Contribute?

### 🐛 Reporting Bugs

Before creating bug reports, please check the existing issues to avoid duplicates. When you create a bug report, include as many details as possible:

- **Use a clear and descriptive title**
- **Describe the exact steps to reproduce the problem**
- **Describe the behavior you observed and what you expected to see**
- **Include your environment details** (device, Android version, PC OS, etc.)

### ✨ Suggesting Enhancements

Enhancement suggestions are tracked as GitHub issues. When suggesting an enhancement:

- **Use a clear and descriptive title**
- **Provide a step-by-step description of the suggested enhancement**
- **Explain why this enhancement would be useful**

### 📝 Pull Requests

1. **Fork the repo** and create your branch from `main`
2. **Follow the existing code style** (Kotlin official style, 4-space indent for C)
3. **Write meaningful commit messages**
4. **Update documentation** if you're changing functionality
5. **Make sure the test suite passes**
6. **Create the Pull Request**

## Development Setup

### Prerequisites

- Android Studio (Hedgehog or later) or VS Code + Android SDK
- JDK 17
- Android SDK 36
- USB gamepad (for testing)
- Windows 10/11 PC (for testing receiver.exe)

### Setup Steps

```bash
# Clone your fork
git clone https://github.com/your-username/HidBridge.git
cd HidBridge

# Create a branch
git checkout -b feature/your-feature

# Build
./gradlew assembleDebug

# Install on device
adb install app-debug.apk
```

## Coding Standards

### Kotlin (Android)

- Follow [Kotlin official coding style](https://developer.android.com/kotlin/style-guide)
- Use `suspend` functions for async operations
- Use `StateFlow` for observable state
- Compose with Material3 components
- Document public APIs with KDoc

### C (PC receiver.exe)

- C11 standard
- 4-space indentation
- Static linking (no DLL dependencies)
- Windows API functions with wide character variants (`CreateWindowW`, not `CreateWindowA`)
- Error handling with descriptive messages

## Project Structure

```
HidBridge/
├── app/src/main/
│   ├── java/dev/hid/demo/
│   │   ├── bluetooth/     # HID device management
│   │   ├── input/         # Input bridging + UDP
│   │   ├── service/       # Android services
│   │   ├── ui/            # Compose UI
│   │   └── wifi/          # RFCOMM + state machine
│   └── res/               # Resources
└── build.gradle.kts
```

## Need Help?

Feel free to open an issue with a question tag. We'll do our best to help!
