-- Add type column to pillars
ALTER TABLE pillars ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'STANDARD';
