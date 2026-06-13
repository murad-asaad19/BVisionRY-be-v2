-- Update overall_summary_prompt with 5 named cross-pillar connections from holistic analysis doc
UPDATE pipelines SET overall_summary_prompt =
'Synthesize results from all evaluated pillars into a holistic development summary.

CROSS-PILLAR ANALYSIS RULES:
1. If the same gap appears in 3 or more pillars, it is ONE core pattern — name it once clearly, reference it briefly in relevant areas, explain it fully in recommendations.
2. Look for the "strong with systems, developing with people" pattern: high scores in data/process pillars (Objectivity, Focus, Discipline) but lower in people pillars (Listening, Curiosity, Open-Mindedness).
3. Check for contradictions: a perfect exercise score but vague real-world descriptions means the skill exists on paper but is not applied. Adjust your assessment accordingly.

THE 5 CRITICAL CROSS-PILLAR CONNECTIONS (always check these):

CONNECTION 1: Obstacles <-> Responsibility (Mirror Check)
These are mirrors of each other. Responsibility asks how they handled a past failure; Obstacles asks what blocks them now.
- If they take full ownership in Responsibility but describe their current Obstacle vaguely — they own the past but not the present. Flag this.
- If they blame others in Responsibility AND their Obstacle is about something someone else is not doing — this is a consistent external focus pattern. It should inform both scores.
- If the obstacle is recurring (3+ months stuck) — check if they acknowledged their own role in that stall.

CONNECTION 2: Listening <-> Objectivity <-> Curiosity (The People Triangle)
These three travel together. A weakness in one almost always appears in the other two — applied to people and emotions rather than systems and data.
- Listening: Do they pick up on feelings, or just facts?
- Objectivity: Can they rewrite people-related statements without adding a different judgment?
- Curiosity: Do their questions go beyond data — do they ask about motivations, feelings, context?
- If someone scores low on all three: name the pattern clearly as "strong with systems, developing with people". Do NOT write three separate growth edges that say the same thing.

CONNECTION 3: Vision <-> Energy & Motivation <-> Discipline (The Fuel Chain)
- Weak Vision + Pain-Driven Motivation = running away from something but no clear destination. High burnout risk.
- Strong Vision + No Discipline habits = can see the destination but not building daily systems to get there.
- Strong Discipline + Weak Vision = executing well but may be working on the wrong things. Productive but potentially directionless.

CONNECTION 4: Growth Mindset <-> Open-Mindedness <-> Obstacles (The Action Chain)
These tell you whether this person genuinely moves when things go wrong — or stays stuck.
- Growth Mindset: do they believe they can change?
- Open-Mindedness: can they actually consider a different way of doing things?
- Obstacles: when concretely stuck, do they reframe and act — or analyze and stall?
- Key check: Someone can score high on Growth Mindset (believes in growth) while scoring low on Obstacles (cannot apply that belief when stuck). This is the gap between knowing and doing.

CONNECTION 5: Discipline <-> Focus/Flow <-> Obstacles (The Execution Stack)
These reveal actual execution capacity — not just intention.
- No restorative habits (sleep, recovery) = decision quality degrades over time. Check if Obstacles are exhaustion-driven vs strategic.
- No cognitive fuel habits (reading, learning) = shallow problem definitions in Obstacles are likely.
- Reactive Focus/Flow = urgency runs the day. Check: are their Obstacles about firefighting rather than long-term bottlenecks?

SUMMARY PRINCIPLES:
- Gaps are called "growth edges" — not weaknesses
- Build on existing foundations — they already know a lot
- Connect mindset patterns to real business outcomes
- Provide prioritized, actionable recommendations
- Name the core pattern first, then detail specific areas
- In the "movingForward" field: list top 3 strengths with percentages, bottom 3 growth edges with percentages, the core pattern thread that ties growth edges together, and what focused work on these areas creates for their business';
