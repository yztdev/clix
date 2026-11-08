# CLIX Local-only runtime and global environment configuration

## Current CLI behavior

- `clix` starts the local Agent in the current working directory.
- `clix remote`, `clix login`, and `clix serve --remote` are disabled in this local-only release.
- Remote source code is retained for a later release, but these entry points are not available from the CLI.

## Single persistent environment file

CLIX reads its persistent `.env` values only from:

- Windows: `%USERPROFILE%\.clix\.env`
- Linux/macOS: `~/.clix/.env`

Project `.env` files are not read by the application. Process environment variables and JVM system properties can still override persistent values for temporary or managed deployments.

On first startup without a usable API key, CLIX creates the global file with a commented template and prints its exact path. Add the required provider key, for example:

```dotenv
LLM_PROVIDER=deepseek
DEEPSEEK_API_KEY=your-key
```

Do not commit this file or share its contents.

## Windows command installation

Build the shaded JAR, create a Java 17 runtime image on the build machine, then install the command wrapper:

```powershell
mvn -Pquick -DskipTests clean package
.\tools\build-clix-runtime-windows.ps1
.\tools\install-clix-windows.ps1
```

The installer creates `%LOCALAPPDATA%\clix\bin\clix.cmd`, places the JAR and bundled runtime under `%LOCALAPPDATA%\clix`, and adds the `bin` directory to the user's PATH. Reopen the terminal and run `clix` from any project directory.

The build machine needs JDK 17 (including `jlink`). End-user machines do not need Java, Maven, CMake, GCC, or Visual Studio.

This CMD launcher does not provide the native launcher's named-pipe Ctrl+C bridge. Ctrl+C behavior must be verified through the Java/JLine console path before claiming parity with the native launcher.
