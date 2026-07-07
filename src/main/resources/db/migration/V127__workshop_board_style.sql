-- Admin-selected live-board road style ('lanes', 'track', 'trail', 'bars').
-- NULL = frontend default ('lanes').
ALTER TABLE workshops ADD COLUMN board_style varchar(24);
