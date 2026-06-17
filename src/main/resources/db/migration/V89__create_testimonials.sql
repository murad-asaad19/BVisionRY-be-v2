-- Homepage testimonials were previously hardcoded in the frontend
-- (web/src/lib/founder-content.ts FRI_TESTIMONIALS). This migration moves them
-- to a managed table so super admins can add, edit, hide, reorder and attach a
-- photo to each one without a code change.

CREATE TABLE testimonials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(160) NOT NULL,
    title VARCHAR(200),
    quote TEXT NOT NULL,
    year INTEGER,
    rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
    photo_url VARCHAR(512),
    published BOOLEAN NOT NULL DEFAULT TRUE,
    display_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_testimonials_published_order
    ON testimonials (published, display_order, created_at);

-- Seed the real named testimonial first so it leads the marquee.
INSERT INTO testimonials (name, title, quote, year, rating, display_order)
VALUES (
    'Ahmed Swailem',
    NULL,
    $$The training was truly empowering. It gave me a clear vision of what success looks like and how to move toward it with intention.

I strongly believe the insights from the workshops will make my next journey smoother, with a scalable mindset focused on measurable objectives and clear goals. It also helped me shift my perspective from focusing on blame to identifying gaps, closing them, and continuously improving.

Overall, the training encouraged me to further develop my leadership skills and approach challenges with a structured and growth-oriented mindset. Thank you Bvisionry, for the guidance and inspiration.$$,
    2026,
    5,
    1
);

-- Migrate the six placeholders that previously lived in FRI_TESTIMONIALS
-- (author -> name, org -> title). Year is unknown for these, left NULL.
INSERT INTO testimonials (name, title, quote, rating, display_order) VALUES
('Program Director', 'Leading MENA Accelerator',
 'The FRI assessment gave us the data we were missing. We stopped guessing which founders were ready and started making informed selection decisions.', 5, 2),
('Managing Partner', 'Early Stage VC Fund',
 'For the first time, we could show our LPs a measurable framework for how we evaluate founder quality beyond the pitch deck. Game-changing for due diligence.', 5, 3),
('Entrepreneurship Center Director', 'Regional University',
 'Our students don''t just learn entrepreneurship theory anymore. They see their readiness scores, understand their gaps, and have a roadmap for development.', 5, 4),
('Program Director', 'MENA Accelerator',
 'Finally a framework that matches how founders actually think. Our selection meetings went from debate to decision.', 5, 5),
('Investment Partner', 'Seed Fund, GCC',
 'We used to track founder signals in spreadsheets and gut feel. Now it''s one index we can defend to our LPs.', 5, 6),
('Venture Lab Lead', 'University Program',
 'Super thoughtful build. Everything has a reason, and our faculty finally has progress data to report.', 5, 7);
