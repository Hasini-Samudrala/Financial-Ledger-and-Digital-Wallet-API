

**Description:** Summarizes technical articles, tutorials, and documentation into structured Obsidian notes.

**Instructions for the AI:**
When I give you a topic, link, or text to ingest, you must generate a Markdown note following these exact rules:
1. Include a YAML frontmatter block at the top with `date`, `tags`, and `source`.
2. Write a 2-3 sentence summary of the core concept.
3. List 3-5 key takeaways using bullet points.
4. Add a "Relevance to Financial Ledger" section explaining how this helps our specific digital wallet API project.
5. Suggest 2-3 related [[wikilinks]] at the bottom.
6. Do not use conversational filler; output only the raw Markdown text.




---
name: brain-ingest
description: Summarizes technical articles, tutorials, documentation, and supplied text into structured Obsidian notes relevant to the Financial Ledger project.
---

# Skill: brain-ingest

## Purpose
Summarize technical resources into concise, structured, and reusable Obsidian notes.

## Instructions for the AI

When given a topic, link, article, tutorial, documentation, or text to ingest, generate a Markdown note following these rules:

1. Begin with YAML frontmatter containing:
   - `date`: Date of note creation in YYYY-MM-DD format.
   - `tags`: Relevant tags as a YAML list.
   - `source`: Original URL, document title, or "User-provided text" when applicable.

2. Include a **Summary** section containing 2–3 sentences explaining the core concept.

3. Include a **Key Takeaways** section containing 3–5 concise bullet points covering the most important concepts.

4. Include a **Relevance to Financial Ledger** section:
   - Explain how the concept relates to our Digital Wallet API.
   - Connect it to relevant functional requirements (FR-1 to FR-5), non-functional requirements, or implementation decisions wherever applicable.
   - If the concept has no direct relevance, state that clearly instead of forcing a connection.

5. Include 2–3 relevant Obsidian `[[wikilinks]]` at the bottom under a **Related Notes** section.

6. Maintain technical accuracy:
   - Do not invent facts or unsupported explanations.
   - Preserve important technical terminology.
   - Do not omit critical limitations or assumptions mentioned in the source.
   - If the source does not provide enough information, explicitly mention the limitation.
   - Clearly distinguish source-derived information from additional interpretation.

7. Keep notes concise, readable, and useful for future reference.

8. Output only the raw Markdown note. Do not include conversational introductions, explanations, or closing remarks.



Since you are building the Financial Ledger API using your existing Java and Spring Boot experience, these 10 research topics cover exactly what you need to prove your design decisions for the database, concurrency, and API architecture.

  

You can copy and paste these exact prompts one by one, along with your `brain-ingest` instructions, to generate your 10 notes:

  

1. **Topic 1 (Core Concept):** Please ingest this topic: The principles of double-entry accounting in database design and why every credit must equal a debit.
    
      
    
2. **Topic 2 (Database Choice):** Please ingest this topic: How SQLite provides ACID transactions by default using BEGIN, COMMIT, and ROLLBACK commands.
    
      
    
3. **Topic 3 (Concurrency FR-3):** Please ingest this topic: How SQLite WAL (Write-Ahead Logging) mode prevents database locking and handles concurrent transactions safely.
    
      
    
4. **Topic 4 (Data Integrity):** Please ingest this topic: Why storing monetary amounts as floating-point decimals causes rounding errors, and how to use integer minor units (like paise) instead.
    
      
    
5. **Topic 5 (Network Safety FR-2):** Please ingest this topic: What is an API Idempotency Key, and how does it prevent double-charging a user during network retries?
    
      
    
6. **Topic 6 (Audit Trail FR-4):** Please ingest this topic: Designing an immutable audit trail in a database using reversal transactions instead of UPDATE or DELETE queries.
    
      
    
7. **Topic 7 (Spring Boot Logic):** Please ingest this topic: How to use Spring Boot's `@Transactional` annotation to ensure a database transfer either fully succeeds or fully rolls back.
    
      
    
8. **Topic 8 (Testing Strategy TQ-2):** Please ingest this topic: How to write integration tests to verify database rollback behavior when a financial transfer fails.
    
      
    
9. **Topic 9 (System Safety TQ-4):** Please ingest this topic: Common race conditions in digital wallets and how database transaction locks prevent balance corruption.
    
      
    
10. **Topic 10 (Reconciliation FR-5):** Please ingest this topic: Designing a backend reconciliation process to verify that total system debits match total system credits.
    
      
    

Run each of these through your AI assistant, save the generated markdown text into 10 separate `.md` files in your `brain/inbox/` folder, and commit them to GitHub. Once those are merged, Stage 1 of your SDLC is officially complete!