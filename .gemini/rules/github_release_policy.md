---
description: Mandatory GitHub repository release policy for BINGO
globs: **/*
---

# GitHub Release Policy for BINGO

Every time a release APK is generated (`assembleRelease`):
1. Copy the signed release APK to both:
   - `/home/bob/workspace/Bingo.apk`
   - `/home/bob/workspace/bingo_multiplayer/Bingo.apk`
2. Commit the updated codebase and `Bingo.apk` to Git with a descriptive commit message.
3. Push the commit to GitHub remote `https://github.com/VamsiReddyBora/BINGO.git` on branch `main`.
4. Ensure `.gitignore` never excludes `Bingo.apk`.
