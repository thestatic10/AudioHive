# AudioHive

AudioHive is an Android application that lets you listen to music together with friends, share Spotify queues, and control playback collaboratively.

## Features

- Create and join music sessions
- Real-time Spotify authentication and playback control
- Share session codes to invite friends
- Modern Material Design UI
- Room-based session management

## Project Structure

```
.
├── app/                        # Android app source code and resources
├── build.gradle                # Project-level Gradle build file
├── gradle.properties           # Gradle configuration properties
├── gradlew / gradlew.bat       # Gradle wrapper scripts
├── local.properties            # Local environment properties
├── settings.gradle             # Gradle settings
├── .idea/                      # IDE configuration files
├── build/                      # Build outputs
├── gradle/                     # Gradle wrapper files
├── wrapper/                    # Gradle distribution
├── AudioHive_Documentation.docx# Project documentation
```

## Requirements

- Android Studio (latest recommended)
- JDK 21+
- Gradle 8.14.2 (wrapper included)
- Spotify Developer Account (for authentication)

## Build & Run

1. **Clone the repository:**
   ```sh
   git clone 
   ```

2. **Open in Android Studio**  
   Import the project as an Android project.

3. **Build the project:**
   ```sh
   ./gradlew build
   ```

4. **Run on device/emulator:**  
   Use Android Studio's Run button or:
   ```sh
   ./gradlew installDebug
   ```

## Configuration

- **Spotify Authentication:**  
  Set up your Spotify client ID and secret in the appropriate configuration files or environment variables.

- **Gradle Properties:**  
  See [gradle.properties](gradle.properties) for JVM and AndroidX settings.

## Documentation

- See [AudioHive_Documentation.docx](AudioHive_Documentation.docx) for detailed documentation.

## License

This project uses open-source dependencies. See individual library licenses in the `wrapper/dists/gradle-8.14.2-bin/` directory.

---

For questions or contributions, please open an issue or submit a pull request.