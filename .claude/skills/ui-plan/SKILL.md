---
name: ui-plan
description: Before building any app with a user interface (Android app, Windows/desktop window app, PySide/Qt/Tk tool, web or browser front end), hand the UI design to a planning agent with a fixed brief and get back a written UI plan (screens, navigation, wireframes, every state, platform conventions, icon) that the build then follows (user rule). Use when designing or building a new UI app, adding a screen or window to one, or redesigning an existing UI.
---

# ui-plan

User rule (Nov-09-2026): when designing a UI app (window app, desktop tool, Android app, web
front end), **use an agent to plan the UI** before writing UI code. The main session keeps the
non-UI work moving (repo, data layer, engine, tests) while the agent plans, then reviews the
plan and builds to it.

Why an agent: a UI planned inline gets planned in passing, one screen at a time, while the code
is being written. The failures the platform skills record (a feature whose permission takes two
gestures, an About screen nobody can reach, an empty log pane, a state nobody drew) are mostly
states and routes that were never planned. A separate planner with a fixed output contract has
to list every screen and every state before anyone writes a layout.

## When

- A new app or tool that has any UI: run it in the **plan** step of the new-project flow
  (`repo-init`), after the repo exists and before UI code.
- A new screen, window, tab or dialog in an existing app, or a redesign.
- Not for a one-line UI fix (a label, a colour, a padding value).

## How

Launch one agent (the `Plan` agent type, read-only) in the background, at the same time as the
non-UI work. One agent per app, not one per screen: the navigation and the shared states only
make sense planned together. The brief is the only context it gets, so it must be complete:

```text
Goal: plan the UI for <app name>, a <platform: Android / Windows desktop (PySide6) / web> app that <one-line purpose>.
Users and context: <who uses it, where, one-handed / at a desk / on a shared screen>.
Features (from the request): <list>. Defaults already decided: <list, e.g. offline, signed release>.
Platform rules to follow: <paths to the matching skill/rule files, e.g.
  D:\GitHub\engineering-hub\rules\android-app\android-app.mdc,
  ~/.claude/skills/android-permissions/SKILL.md, ~/.claude/skills/android-about-page/SKILL.md,
  ~/.claude/skills/pyside-desktop-app/SKILL.md>.
Read those before planning. Do not write code. Return exactly the sections below.
```

### The output contract (the agent returns all of these)

1. **Screen / window map:** every screen, window, tab and dialog, and the navigation between them
   (a list or an ASCII tree). The About tab (Android) and any Settings belong here.
2. **Wireframe per screen:** an ASCII layout of each screen at phone width (Android) or the
   default window size (desktop), naming each control.
3. **States per screen:** first run, loading/working, empty, success, error, and the
   platform-specific ones: permission not granted and permanently refused, no camera / no
   device / offline, a long-running job in progress and cancelled. Say what the user sees and
   can do in each.
4. **Primary flows:** the main task as numbered steps, with the gesture count from intent to the
   result (and to any permission dialog, which must be one gesture from the feature).
5. **Platform conventions:** Material 3 and edge-to-edge insets, back behaviour, font scale (Android);
   window/taskbar icon, a responsive GUI thread, keyboard shortcuts, Windows light/dark (desktop).
6. **Visual system:** colours as named tokens, type scale, spacing, light and dark, and the app
   icon concept (to be drawn in code, checked at 16 and 256 px).
7. **Accessibility:** contrast, touch target size (48 dp) or keyboard reachability, content
   descriptions, text that survives 130% font scale.
8. **Open questions and the default taken for each,** so the build never stops to ask.

## After the agent reports

- Read the plan against the request and the platform skills. Fix anything that contradicts a rule
  (a permission behind a second control, no About tab, work on the GUI thread) before building.
- Save it in the repo as `docs/UI-PLAN.md` and commit it with the plan. It is what the screens
  are checked against later.
- Build to it. When the build has to deviate, update `docs/UI-PLAN.md` in the same commit,
  so the plan never describes an app that does not exist.
- When verifying on a device or in a screenshot, walk the plan's state list, not just the happy
  path: each state in section 3 should be reachable and look as planned.
