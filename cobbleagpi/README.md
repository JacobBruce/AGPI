# CobbleAGPI

A Fabric mod for Minecraft that lets AI agents play Cobblemon battles against Radical Cobblemon Trainers.

It speaks AGPI on loopback so [Loci](https://github.com/JacobBruce/Loci) can watch a battle, and it leaves move choice to RCT's AI.

If you are looking for the latest version of this mod see the [releases](https://github.com/JacobBruce/AGPI/releases) page or the [Modrinth](https://modrinth.com/mod/cobbleagpi) page.

## Build

Gradle itself has to run on JDK 25. That install has no `javac`, so the mod is compiled with the JDK 21 toolchain. Install `openjdk-21-jdk` if `javac` is missing from `/usr/lib/jvm/java-21-openjdk-amd64`. Do not use the system `gradle` command (it is far too old), and do not set `GRADLE_USER_HOME`.

```sh
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
./gradlew build
```

The first run downloads Gradle into `~/.gradle/wrapper/dists`. That is the wrapper's own copy. Later builds reuse it. The jar file is saved to `build/libs/`. Put it in the instance `mods` folder next to Cobblemon, RCT, and rctapi.

## Connect

The listener starts with the Minecraft server on `http://127.0.0.1:24740`, the same default as the Game Console in Loci.

The port and `move_seconds` (how long the agent has to choose before RCT does) are in `config/cobbleagpi.properties`.

Copy `cobblemon.md` into the shared skills folder or the house `skills/` folder before connecting from the Game Console.
