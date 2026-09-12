# 4Form

**An agent that fills job applications inside the browser you already use, and refuses to make things up about you.**

Built for *Agents, Everywhere: Bots, Channels, & More* (AI Tinkerers Global Hackathon, September 12, 2026).

---

## The problem

Applying to jobs is not hard because writing is hard. It is hard because the same twenty facts about you get retyped into a different form every single time, and each form asks them differently.

Browser autofill solves five of those fields. Name, email, phone, address, city. Then it stops.

Here is a real application screen from Ashby, one of the most used hiring platforms in tech:

```
How many years of professional UX/UI or product design experience do you have?
Please paste a link to your portfolio.
Roughly what share of your portfolio is web or mobile product design?
What are your salary expectations for this role (gross annual)?
```

Four fields. **Zero of them are canonical.** Chrome writes nothing here. Every one of them needs someone who has actually read your CV.

## What 4Form does

It lives **inside the browser**, as an Android AccessibilityService. You open the job form, tap the accessibility button, and a bubble appears in the corner. The bubble is the agent.

It then walks the form the way a person does: reads what is on screen, fills what your profile supports, scrolls, reads again, and keeps going until the page stops moving.

**And when your CV does not answer the question, it leaves the field empty and tells you why.**

That last sentence is the product.

---

## Why the environment is the point

This agent could not exist in a chat window.

A chatbot can draft answers. It cannot see that the Ashby form calls every one of its four inputs `Type here...`, work out that the real question is the paragraph 40 pixels above, and write into the field. That capability comes from being *in* the browser, reading the same accessibility tree that a screen reader reads.

Move 4Form into a chatbox and the value does not shrink. It disappears.

---

## The rule that makes it trustworthy

Everything the agent writes must trace back to something you declared. We call it **the anchor**.

Four categories, and the agent behaves differently in each:

| Category | Example | What the agent does |
|---|---|---|
| **1 · Canonical short field** | name, email, phone | Writes from your profile, literally. No model involved. |
| **2 · Long question in prose** | *"How many years of professional UX/UI experience do you have?"* | Answers from your CV, citing the anchor it came from. |
| **3 · Choice** | radio, checkbox | Ticks only what your CV supports. Never guesses. |
| **4 · Question your CV cannot answer** | *"What are your salary expectations?"* | **Leaves it blank and says why.** |

Category 4 is not a limitation we are apologising for. It is the feature.

> *"Your competitor would guess a number here. I won't."*

A tool that writes in your name and invents a salary expectation, a visa status, or a start date is not saving you time. It is creating a problem you will find out about in an interview.

**A fifth case, and it is not the same as category 4.** Category 4 is "your CV doesn't answer this." Some fields are the opposite: the CV *could* answer them, and the agent still won't, because the answer isn't data, it's a decision that belongs to the person. Salary expectations, availability to start, gender, ethnicity, disability, "I declare that this information is true": these are reserved by rule, not by a missing fact. Fill in "salary expectations: 55k" on your profile and the field still stays blank, on purpose, and the panel says so in a different, calmer tone than "I couldn't find this" — because it is not the same failure. It is not a failure at all.

---

## The three acts

**1 · It reads the screen.** The accessibility tree only contains what is *rendered*, so scanning a static screen never sees a three page form. The engine scans, acts, scrolls, scans again, and stops only when the screen stops moving.

**2 · It writes what it can defend.** Text fields and choices go into one queue, in visual order, so the agent moves down the form the way your eye does instead of stopping to ask permission halfway.

**3 · It learns from your corrections.** Fix a field the agent filled, and that correction becomes a rule. The learned value beats the profile value, and the most recent correction beats the older one, because a correction is the strongest signal a user can send.

---

## Engineering notes

The interesting parts of this codebase are the failure paths, because that is where a screen reading agent actually lives.

**The label ladder.** A field is named by the most trustworthy signal available: `labeledBy` → `hint` → `contentDescription` → readable `viewId` → sibling text → nearest neighbour by geometry. The level that resolved each field is recorded, and the app learns which level works best per app.

**A placeholder is not a label.** Ashby gives all four fields the same hint, `Type here...`. Since `hint` sits high on the ladder, the agent named all four fields "Type here", matched nothing, and burned a network call asking a model what a placeholder means. Two defences now: a list of generic placeholder patterns, and a rule that a hint repeated across fields on the same screen names none of them. The second one survives translation into any language.

**Scrolling has two mechanisms, and one of them lies.** `ACTION_SCROLL_FORWARD` returns success inside a WebView and moves nothing (measured: 207 nodes before, 207 after). So the engine falls back to a drag gesture. Then the drag gesture turned out to have its own failure: `dispatchGesture` dispatches, the gesture happens, and neither `onCompleted` nor `onCancelled` ever fires. Measured across six consecutive runs. The fix is not to chase the missing callback, it is to stop depending on a single source of truth: dispatch the gesture *and* schedule the continuation on a timer, first one wins.

**The loop has a watchdog.** If no step happens for 12 seconds, the round ends and says so. Silent hangs are worse than reported failures.

**Passwords are never read.** Not stored, not logged, not sent. The check happens during the scan, before any decision.

**Diagnostics carry no personal data.** Every round writes a JSON file with labels, resolution levels and decisions, never the values written. It is what made the bugs above findable, and it ships with a test proving the values stay out.

---

## Running it

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest   # 142 tests
```

Install, enable 4Form in Android accessibility settings, open a job application, and tap the accessibility button.

The optional language model bridge reads its endpoint from `local.properties`, which is gitignored. Clone this repo and it compiles with an empty endpoint, and the app degrades gracefully to profile only matching. No secret lives in source.

**One dependency, deliberate.** The app carried zero runtime dependencies until 12/09, when a real resume showed up as a PDF. A PDF is a compressed stream, not text, and Android's own `PdfRenderer` only rasterizes a page as an image, it does not extract text. `com.tom-roush:pdfbox-android` reads the PDF locally, on the device, and the file itself is never kept: text goes to the same confirmation screen as `.md`/`.txt`, the PDF bytes are discarded right after.

---

## Honest status

Built in one day. What is proven on a real device, on real job forms:

- writing into named, empty web fields on Greenhouse and Ashby
- the scroll and fill loop running to the end of a form
- refusing salary expectations, availability, sensitive identity fields, and legal declarations by RULE, even when the profile declares the value
- the bubble, its states, and the panel it opens

What is not proven yet: the learned correction winning on a later round. The write is measured. The replay is not.

We would rather say that than let you assume it.

---

## What was built during the hackathon

This project follows the event's build eligibility rule, so here is the line, drawn honestly.

**Built today, during the hackathon.** The agent itself: **1,539 lines across 18 files, plus 3 new ones**, all measured in git.

- **The bubble.** A 60dp overlay window that is the agent's entire presence. Drag it, it moves. Drop it on the bin, it pops and the round ends. Tap it, it opens the panel. Five visual states, drawn on Canvas, differing by movement rather than colour.
- **The unified queue.** Text fields and choices in one list, in visual order. Before today the agent filled three fields, hit a checkbox and stopped to ask. Now it decides and keeps going.
- **The refusal rule.** Four categories of field, and the one that matters: a question the CV cannot answer is left blank with a reason, never guessed.
- **Failure handling.** A watchdog that ends a stalled round instead of spinning forever, and a scroll gesture backed by a timer because `dispatchGesture` can dispatch, succeed, and never call back. Both found by reading real device diagnostics today.
- **Two matching bugs found on a live job form and fixed.** Generic placeholders are no longer treated as labels, and the bilingual key table was wired into the matching path, where it had never been connected.

**Reused building block, from earlier work.** The `scan/` package: a screen reading layer built before this event, including a diagnostics probe shared with a previous Android project of mine. It walks the accessibility tree, names fields through a fallback ladder, and matches those names against profile keys.

It is infrastructure. It knows how to *read* a screen. It does not know what an agent is, when to refuse, or how to behave when a form fights back. That is what was built today, and that is the project.

The split is not rhetorical: it is the package layout. `scan/` is the component. `agent/` and the UI layer are 4Form.
