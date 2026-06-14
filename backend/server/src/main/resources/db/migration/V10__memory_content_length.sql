-- ===========================================================================
-- V10: enforce the memory `content` length contract (≤ 1500 chars) at the DB.
--
-- The ≤1500 limit is part of the entry contract (MCP tool docs + decisions) but
-- was only enforced in the console form — a direct REST/MCP caller could persist
-- unbounded content. The application now validates on both write paths
-- (MemoryContentValidator); this CHECK is the defence-in-depth backstop.
--
-- Additive constraint; safe to apply (verified: no live row exceeds 1500, max
-- observed 991). RLS unaffected.
-- ===========================================================================

ALTER TABLE memory ADD CONSTRAINT memory_content_len
    CHECK (char_length(content) <= 1500);
