-- Exercises: admin-authored, sheet-like tables (fixed columns, member-added
-- rows) that replace the Google-Sheet workflow. Members fill and edit their
-- copy at any time; admins review submissions and leave comments anchored to a
-- cell, a whole column, a whole row, or the submission overall. Unlike
-- assessments there is no AI involvement anywhere in this loop.

CREATE TABLE exercise_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE exercise_columns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id UUID NOT NULL REFERENCES exercise_templates(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    type VARCHAR(32) NOT NULL
        CHECK (type IN ('TEXT', 'LONG_TEXT', 'NUMBER', 'DATE', 'SELECT')),
    config_json JSONB,
    display_order INTEGER NOT NULL DEFAULT 0,
    is_required BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_exercise_columns_template
    ON exercise_columns (template_id, display_order);

-- Mirrors assignments: user_id NULL = org-level provision (super admin grants
-- the template to the org), user_id set = one template×member assignment.
CREATE TABLE exercise_assignments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id UUID NOT NULL REFERENCES exercise_templates(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    assigned_by UUID NOT NULL,
    deadline TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- At most one provision row per (org, template).
CREATE UNIQUE INDEX uq_exercise_assignments_provision
    ON exercise_assignments (organization_id, template_id)
    WHERE user_id IS NULL;

-- A member gets a template at most once per org.
CREATE UNIQUE INDEX uq_exercise_assignments_member
    ON exercise_assignments (organization_id, template_id, user_id)
    WHERE user_id IS NOT NULL;

CREATE INDEX idx_exercise_assignments_org
    ON exercise_assignments (organization_id, created_at DESC);

CREATE TABLE exercise_submissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id UUID NOT NULL REFERENCES exercise_assignments(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS'
        CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'CHANGES_REQUESTED', 'REVIEWED')),
    last_saved_at TIMESTAMP WITH TIME ZONE,
    submitted_at TIMESTAMP WITH TIME ZONE,
    reviewed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_exercise_submissions_assignment UNIQUE (assignment_id)
);

CREATE INDEX idx_exercise_submissions_user
    ON exercise_submissions (user_id, created_at DESC);

-- Member-added rows. cells maps column id -> value. Soft-deleted (deleted_at)
-- instead of removed so a comment anchored to the row never loses its target.
CREATE TABLE exercise_rows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id UUID NOT NULL REFERENCES exercise_submissions(id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL DEFAULT 0,
    cells JSONB,
    deleted_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_exercise_rows_submission
    ON exercise_rows (submission_id, display_order);

-- Review-loop comments. Anchor semantics: row+column = one cell, column only =
-- whole column, row only = whole row, neither = the submission overall.
-- cell_value_snapshot freezes the commented value so the thread stays readable
-- after the member edits the cell to address it. parent_id threads replies
-- under a root comment; status/resolved_* are only meaningful on roots.
CREATE TABLE exercise_comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id UUID NOT NULL REFERENCES exercise_submissions(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    row_id UUID REFERENCES exercise_rows(id) ON DELETE CASCADE,
    column_id UUID REFERENCES exercise_columns(id) ON DELETE CASCADE,
    parent_id UUID REFERENCES exercise_comments(id) ON DELETE CASCADE,
    body TEXT NOT NULL,
    cell_value_snapshot TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'RESOLVED')),
    resolved_by UUID,
    resolved_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_exercise_comments_submission
    ON exercise_comments (submission_id, created_at);
