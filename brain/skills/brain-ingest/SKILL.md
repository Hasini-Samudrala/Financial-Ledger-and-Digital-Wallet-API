---
name: brain-ingest
description: Summarizes technical articles, tutorials, documentation, and supplied text into structured Obsidian notes relevant to the Financial Ledger project.
---
# Skill: brain-ingest

## Purpose
Summarize technical resources into concise, structured, and reusable Obsidian notes.


## Instructions for the AI

When given a topic, link, article, tutorial, documentation, or text to ingest, create a clear and concise Markdown research note.

Follow these rules:

1. **YAML Frontmatter**
    - Include `date`, `tags`, and `source`.
    - Use valid YAML formatting.
2. **Summary**
    - Explain the main concept in 2–3 simple sentences.
    - Assume the reader is a beginner.
    - Avoid unnecessary technical details.
3. **Key Takeaways**
    - Include 3–5 important points from the source.
    - Explain the main ideas, not minor details or terminology lists.
    - Use simple language that a teammate can understand without additional research.
    - Prioritise what the reader should remember after reading the article.
    - Do not introduce unrelated concepts just to fill the list.
4. **Relevance to Financial Ledger**
    - Explain how the concept helps us understand or build our Digital Wallet API.
    - Connect it to relevant FR, NFR, or implementation decisions where appropriate.
    - Use practical examples from our project when helpful.
    - Keep this section focused. Do not repeat the entire research explanation.
5. **Additional Interpretation**
    - Include this section only when additional explanation or project-specific reasoning is genuinely useful.
    - Clearly label information that comes from outside the source or is our own interpretation.
    - Keep it brief, ideally 1–3 points.
    - Do not force interpretations or introduce unnecessary design discussions.
6. **Source Limitations**
    - Mention limitations only when they materially affect how we use or understand the source.
    - Do not list every topic the source does not discuss.
    - Never attribute external knowledge or assumptions to the original source.
7. **Related Notes**
    - Suggest 2–3 relevant Obsidian `[[wikilinks]]`.
    - Prefer notes that already exist in our vault.
    - Do not invent links to unrelated topics.
8. **Writing Style**
    - Write for students and developers working on this project.
    - Be clear, direct, natural, and beginner-friendly.
    - Explain technical terms when they are necessary.
    - Avoid textbook-style digressions, excessive detail, complicated terminology, and unnecessary repetition.
    - Do not make the note longer just to make it appear comprehensive.
9. **Accuracy**
    - Never invent facts or unsupported explanations.
    - Preserve the source's actual meaning.
    - Do not silently mix information from the source with general knowledge.
    - If the source does not explain something, do not pretend that it does.
10. Output only the raw Markdown note. Do not include conversational introductions or closing remarks.
