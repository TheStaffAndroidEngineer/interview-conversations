# Interview Conversations

Source-code companion for *The Staff Android Engineer*. Chapter explanations, exercises, written solutions and editorial records are distributed with the book, not this repository.

## Setup

Use JDK 27 and this repository’s pinned Gradle wrapper/version catalog.
No Android SDK is needed for this JVM build.

```sh
./gradlew test
./gradlew tasks --all
```

Reference tests run separately from deliberately incomplete learner and negative-compiler tasks. Use the book for exercise task selection.

Local SDK settings, runtime data, generated APKs, captured evidence and book text are excluded. Keep required third-party licences/notices with the code. Original companion code is MIT licensed; third-party materials retain their own licences. The paid book and its written teaching are not included in this licence.

## First-edition code

Use the `first-edition-2026.10` tag with the first edition. The [companion index](https://github.com/TheStaffAndroidEngineer/book-companions) maps chapters to tested versions and the complete source download. For investigations that share tools across projects, use the complete source bundle and its `code/projects/` layout. Detailed reader instructions are supplied with the purchased book.
