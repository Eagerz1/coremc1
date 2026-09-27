package com.coremc.core.progress.adapter;

/**
 * The single seam between this branch and the CoreMC systems that are
 * being built on other branches (Island Progression, Roles/OmniTools,
 * Companions, Season Journey, Quests/Events, Credits/Sky Tokens).
 *
 * <p>Collections and Achievements never import those systems. They ask
 * this registry for an adapter; when the real system is not on this
 * branch the adapter reports {@link ProgressAdapter#available()
 * false}, GUIs show the feature as "coming soon" instead of lying, and
 * rewards that cannot be delivered yet are parked in safe pending
 * storage rather than dropped.</p>
 *
 * <p>Final integration is one call per system — {@code
 * externals.roles(realRoleAdapter)} — with no code in this package to
 * change.</p>
 */
public final class ExternalSystems {

    private IslandProgressionAdapter islands = IslandProgressionAdapter.ABSENT;
    private RoleProgressAdapter roles = RoleProgressAdapter.ABSENT;
    private OmniToolProgressAdapter omniTools = OmniToolProgressAdapter.ABSENT;
    private CompanionProgressAdapter companions = CompanionProgressAdapter.ABSENT;
    private SeasonJourneyAdapter seasonJourney = SeasonJourneyAdapter.ABSENT;
    private QuestEventAdapter questsAndEvents = QuestEventAdapter.ABSENT;
    private MiningCubeAdapter miningCube = MiningCubeAdapter.ABSENT;
    private RewardAdapter rewards = RewardAdapter.ABSENT;

    public IslandProgressionAdapter islands() {
        return islands;
    }

    public void islands(final IslandProgressionAdapter adapter) {
        this.islands = adapter == null ? IslandProgressionAdapter.ABSENT : adapter;
    }

    public RoleProgressAdapter roles() {
        return roles;
    }

    public void roles(final RoleProgressAdapter adapter) {
        this.roles = adapter == null ? RoleProgressAdapter.ABSENT : adapter;
    }

    public OmniToolProgressAdapter omniTools() {
        return omniTools;
    }

    public void omniTools(final OmniToolProgressAdapter adapter) {
        this.omniTools = adapter == null ? OmniToolProgressAdapter.ABSENT : adapter;
    }

    public CompanionProgressAdapter companions() {
        return companions;
    }

    public void companions(final CompanionProgressAdapter adapter) {
        this.companions = adapter == null ? CompanionProgressAdapter.ABSENT : adapter;
    }

    public SeasonJourneyAdapter seasonJourney() {
        return seasonJourney;
    }

    public void seasonJourney(final SeasonJourneyAdapter adapter) {
        this.seasonJourney = adapter == null ? SeasonJourneyAdapter.ABSENT : adapter;
    }

    public QuestEventAdapter questsAndEvents() {
        return questsAndEvents;
    }

    public void questsAndEvents(final QuestEventAdapter adapter) {
        this.questsAndEvents = adapter == null ? QuestEventAdapter.ABSENT : adapter;
    }

    public MiningCubeAdapter miningCube() {
        return miningCube;
    }

    public void miningCube(final MiningCubeAdapter adapter) {
        this.miningCube = adapter == null ? MiningCubeAdapter.ABSENT : adapter;
    }

    public RewardAdapter rewards() {
        return rewards;
    }

    public void rewards(final RewardAdapter adapter) {
        this.rewards = adapter == null ? RewardAdapter.ABSENT : adapter;
    }

    /** Names of the systems that are not on this branch yet (diagnostics/GUI). */
    public String missingSummary() {
        final StringBuilder out = new StringBuilder();
        append(out, "island progression", islands.available());
        append(out, "roles", roles.available());
        append(out, "omnitools", omniTools.available());
        append(out, "companions", companions.available());
        append(out, "season journey", seasonJourney.available());
        append(out, "quests/events", questsAndEvents.available());
        append(out, "mining cube", miningCube.available());
        append(out, "credits/sky tokens", rewards.available());
        return out.length() == 0 ? "none" : out.toString();
    }

    private static void append(final StringBuilder out, final String name, final boolean available) {
        if (!available) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(name);
        }
    }
}
