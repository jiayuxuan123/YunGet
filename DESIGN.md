---
name: YunGet Website
description: Transit wayfinding for turning cloud-drive links into local files.
colors:
  paper: "#f4f0e6"
  paper-raised: "#faf7ef"
  paper-deep: "#e9e2d3"
  ink: "#17201c"
  ink-soft: "#34433b"
  route: "#24745a"
  route-deep: "#18523e"
  route-soft: "#cfe3d8"
  signal: "#e4623c"
  water: "#dceff1"
  yellow: "#f1c84b"
  rule: "#b8b4a6"
typography:
  display:
    fontFamily: "Schibsted Grotesk, Noto Serif SC, Songti SC, STSong, serif"
    fontSize: "clamp(3rem, 7vw, 6.4rem)"
    fontWeight: 400
    lineHeight: 0.98
    letterSpacing: "-0.03em"
  body:
    fontFamily: "Schibsted Grotesk, Microsoft YaHei, PingFang SC, Noto Sans CJK SC, sans-serif"
    fontSize: "17px"
    fontWeight: 400
    lineHeight: 1.65
  label:
    fontFamily: "Schibsted Grotesk, Microsoft YaHei, PingFang SC, sans-serif"
    fontSize: "12px"
    fontWeight: 650
    lineHeight: 1.3
    letterSpacing: "0.02em"
rounded:
  sm: "4px"
  md: "8px"
  lg: "16px"
spacing:
  sm: "8px"
  md: "16px"
  lg: "32px"
  section: "clamp(64px, 8vw, 108px)"
components:
  button-primary:
    backgroundColor: "{colors.route}"
    textColor: "{colors.paper-raised}"
    rounded: "{rounded.sm}"
    padding: "12px 18px"
    height: "44px"
  button-secondary:
    backgroundColor: "{colors.paper-raised}"
    textColor: "{colors.ink}"
    rounded: "{rounded.sm}"
    padding: "12px 18px"
    height: "44px"
---

# Design System: YunGet Website

## Overview

**Creative North Star: "The Download Line"**

YunGet is presented as one continuous route rather than a stack of feature cards. A share link enters at one station, passes through platform authorization and parsing, reaches a direct URL, and ends as a local file. The interface borrows the calm clarity of public transit wayfinding: named stops, route color, directional lines, timetable-like rows, and small operational labels.

The world is friendly but not playful at the expense of trust. It stays mostly light and paper-like, with one strong green route and a restrained signal orange for interruptions. There is no central-server imagery, no cable-ship mythology, and no fake transfer telemetry.

**Key Characteristics:**

- One visible route from link to file.
- Flat surfaces and structural rules instead of floating cards.
- Download and privacy information readable in the first viewport.
- Dense data presented as routes and timetables, not nested panels.
- Motion only reinforces travel through the route.

## Colors

The palette combines warm paper with transit green, water blue, and a small orange signal.

### Primary

- **Route Green** (`#24745a`): primary actions, active route segments, links, and successful state.
- **Deep Route Green** (`#18523e`): hover and high-contrast route labels.

### Secondary

- **Signal Orange** (`#e4623c`): attention and interruption states only.
- **Transit Yellow** (`#f1c84b`): short service notices and selected metadata.
- **Water Blue** (`#dceff1`): explanatory route surfaces.

### Neutral

- **Wayfinding Ink** (`#17201c`): headings, body text, borders.
- **Warm Paper** (`#f4f0e6`): page ground.
- **Raised Paper** (`#faf7ef`): input surfaces and primary reading panels.
- **Rule Stone** (`#b8b4a6`): dividers and structural rules.

**The One Line Rule.** Green is used to show route and action, not as a decorative wash across every section.

## Typography

**Display Font:** Schibsted Grotesk with Noto Serif SC / Songti fallback  
**Body Font:** Schibsted Grotesk with CJK sans fallback  
**Label / Mono Font:** Cascadia Mono / Consolas for file names, versions, hashes, and commands

Display type is large enough to act as station signage. Body copy remains compact and practical. Chinese text falls back intentionally to an editorial serif for display and a legible CJK sans for running copy.

### Hierarchy

- **Display** (400, `clamp(3rem, 7vw, 6.4rem)`, 0.98): homepage statement only.
- **Headline** (400, `clamp(2.2rem, 5vw, 4.2rem)`, 1.02): section destinations.
- **Title** (650, `1.1rem–1.5rem`, 1.25): station and row names.
- **Body** (400, `17px`, 1.65, max 72ch): explanations.
- **Label** (650, `12px`, 0.02em): route status, file facts, and controls.

## Layout

The content width is up to 1240px with fluid page gutters. The homepage uses a continuous vertical route with alternating information density, not equal card rows. The first viewport pairs the product promise with a five-stop route diagram. Desktop uses two-column station layouts where the route line remains continuous; mobile collapses to one route spine with labels above their descriptions. Download pages place the universal APK first, then architecture choices, checksum, installation, and history.

## Elevation & Depth

The system is flat by default. Depth comes from paper tone changes, route lines, and border rules. No glass, blur, hard offset shadows, or decorative halo is used. Focus and selection are communicated with outline and route-color inversion.

## Shapes

Most surfaces are rectangular with small 4–8px radii. Pills are limited to compact status chips and the floating theme control. Circles identify route stops and numbered steps. Rules and lines carry more hierarchy than rounded containers.

## Components

### Buttons

- **Primary:** route green, raised-paper text, 44px minimum height, small radius.
- **Secondary:** paper surface, ink text, 1px rule.
- **Hover / Focus:** deep route green, visible 3px focus outline.

### Route Station

A numbered stop, route segment, label, and short explanation. It may contain a related control but never becomes a generic icon card.

### Tables / Timetables

Used for supported platforms, release files, and platform status. On mobile they become labelled rows, not one card per table row.

### FAQ

Native `details` and `summary`. Answers remain readable with JavaScript disabled.

### Navigation

The desktop header is a compact wayfinding strip with a persistent APK action. On mobile, the APK action remains visible and the menu opens as a focus-managed dialog.

## Do's and Don'ts

### Do

- **Do** show the complete link-to-file route before explaining implementation details.
- **Do** keep download, checksum, and platform limitations visible and precise.
- **Do** use real release data and the official plugin index.
- **Do** preserve reduced-motion content visibility.

### Don't

- **Don't** imply that a YunGet server proxies or stores user downloads.
- **Don't** use fake parser results, speed numbers, or file sizes.
- **Don't** build arrays of identical icon cards.
- **Don't** hide the mobile download action.
- **Don't** use a thick colored side stripe as an accent.
