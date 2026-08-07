# Doujiva — drop-in instructions

Same module shape as `src/en/hentai20/` in the repo.

## Install

1. Copy this whole `doujiva/` folder into your extensions repo at:
   ```
   src/en/doujiva/
   ```
2. Build/format just this module:
   ```
   ./gradlew :src:en:doujiva:spotlessApply
   ./gradlew :src:en:doujiva:assembleDebug
   ```

## Site notes (why it's built this way)

Doujiva is a Next.js (App Router) site, so most of the page ships as escaped React
Server Component "flight" data (`self.__next_f.push(...)`), not plain HTML. That data
*does* contain full manga cards (cover, tags, view counts) — but the same component is
reused for several sidebar "trending" widgets on every page, so blindly grabbing "the
first object with a `manga` key" via `extractNextJs` picks up the wrong list as often
as the right one on these dumps.

Instead, this source uses two things that render as **plain, unescaped markup**:

- **Listing/search grids** (`getPopularManga`/`getLatestUpdates`/search): real
  `<a href="/manga/...">` cards inside a `.content-grid` container — confirmed
  identical across the popular, latest, and search page dumps (25 cards/page).
  Next-page detection uses the `<link rel="next">` tag in `<head>`.
- **Manga details**: a `<script type="application/ld+json">` "CreativeWork" block
  with clean `name`/`author`/`genre`/`numberOfPages` fields — no unescaping needed.
- **Pages**: page images follow a deterministic path,
  `https://cdn.doujiva.com/{slug}/chapter-1/{001..N}.webp`, confirmed from the
  `firstPageUrl` field in the flight data. `numberOfPages` from the same JSON-LD block
  is used to generate the full list directly — no separate reader-page fetch needed
  (the dumps provided didn't include the actual `/manga/{slug}/read/{id}` reader page).

## Not yet verified

- The reader page itself was never fetched in the source dumps, so the CDN page-path
  pattern is inferred from `firstPageUrl` + `numberOfPages`, not confirmed against a
  real multi-page gallery response.
- Entries with more than one chapter (if any exist beyond `chapter-1`) aren't handled —
  every entry is treated as a single-chapter oneshot, matching all four dumps.
- `/search?q=` pagination (`&page=N`) is assumed, not confirmed beyond page 1.
- Nothing has been compiled — no Gradle/network access in the environment that built this.
