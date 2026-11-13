# dumb-gallery

A Metro-style Android gallery, heavily inspired by the Windows Phone Photos app.

- **Pivot hub**: `collection` (every photo and video, grouped by month) and `albums`. Swipe between them; the big lowercase headers slide along like a real pivot.
- **Jump list**: tap a month header to get a year/month tile grid and jump anywhere.
- **Albums**: square cover tiles with name and count. Camera is always first.
- **Google Photos album**: tap **add** to pick photos and videos with the system photo picker, which includes your Google Photos cloud library when Google Photos is set as the cloud media app (picker menu → **Browse**). Picked items keep a persistent read grant and stay in the album; **delete** on them only removes them from the album. Google's API no longer lets third-party apps browse a whole Google Photos library, so picking is the only route. Photos Google Photos keeps on the device already appear in the collection.
- **Viewer**: swipe through photos, pinch or double-tap to zoom, tap to hide the chrome. App bar has share, info and delete; `•••` adds open with, set as and slideshow.
- **Metro touches**: black theme, 20 WP8 accent colours (`•••` → accent colour), turnstile page transitions, tilt on press, an app bar that slides up to show its labels.

## Why it's fast

- Native Kotlin with plain Views. No Compose, no AppCompat, no image library. The only dependencies are RecyclerView and ViewPager2.
- Every view is built in code, so nothing is inflated from XML.
- The MediaStore query starts in `Application.onCreate`, before the first activity draws. It's one query that returns images and videos together, and albums are worked out from the same results.
- Thumbnails come from MediaStore's own thumbnail cache (`loadThumbnail`), so the app never decodes full images for the grid.
- The thumbnail loader puts on-screen tiles first, newest request first. It prefetches the rows you're scrolling towards, drops requests for tiles that scrolled away before they start, and merges duplicate requests.
- Grid tiles skip `requestLayout` when their bitmap changes. Item animators are off, and the recycler cache and pool are larger than the defaults.
- The viewer shows the cached thumbnail straight away, then swaps in a screen-sized hardware bitmap without resetting your zoom.
- Release builds use R8 to minify and strip unused resources.

## Build

You need JDK 17 and the Android SDK (platform 35). Android Studio has both.

```bash
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key so it installs directly. Minimum Android version is 10 (API 29).

## Releases

Pushing a version tag builds the APK on GitHub Actions and publishes a release with the APK attached:

```bash
git tag v1.1 && git push origin v1.1
```

You can also start a release from the **Actions** tab (**release** → **Run workflow**). The version name comes from the tag and the version code from the workflow run number.

To sign releases with your own key, add these repository secrets. Without them each release is debug-signed with a different key, so a new release won't install over the old one.

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password |
