# microPass Engineering Academy

An offline interactive learning application built specifically around the
microPass source code. It assumes no prior knowledge and gradually moves from a
plain-language mental model to distributed-systems and cloud engineering trade-offs.

## Open it

The simplest option is to open `index.html` in a browser. For the most consistent
browser behavior, serve the directory locally from the repository root:

```bash
python3 -m http.server 8088 --directory docs/learning/academy
```

Then visit:

```text
http://localhost:8088
```

No package installation, account, network connection, or backend is required.

## What is interactive

- Clickable request-to-container architecture journey
- Eight guided lessons tied to real microPass files
- Difference lab for commonly confused engineering concepts
- Flip cards with confidence ratings
- Scenario quiz with reasoning explanations
- Interview-answer framework and model responses
- Searchable plain-language glossary
- Personal notes and progress saved in browser `localStorage`
- Dark/light themes, responsive layout, keyboard controls, and reduced-motion support

## Recommended study loop

Use one 45–60 minute session per module:

1. Read the module once without taking detailed notes.
2. Trace the referenced implementation file.
3. Close the lesson and explain it aloud in your own words.
4. Use the flashcards and quiz.
5. Write what remains unclear in the notebook.
6. Answer one interview question for 60–90 seconds.
7. Record a durable summary in `LEARNING_JOURNAL.md`.

Do not measure learning by time spent reading. Measure it by whether you can
predict failure behavior and explain a trade-off without looking at the answer.
