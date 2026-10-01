package cn.mgtown.fmwar.game;

/**
 * 一次阶段计时。
 *
 * @param phase        所属阶段
 * @param startedAtTick 阶段开始的服务器 tick
 * @param durationTicks 持续 tick 数
 */
public record Timer(GamePhase phase, long startedAtTick, long durationTicks) {

    public Timer at(long nowTick, long durationTicks) {
        return new Timer(phase, nowTick, durationTicks);
    }

    public long elapsedTicks(long nowTick) {
        return nowTick - startedAtTick;
    }

    public long remainingTicks(long nowTick) {
        return durationTicks - elapsedTicks(nowTick);
    }

    public boolean expired(long nowTick) {
        return elapsedTicks(nowTick) >= durationTicks;
    }
}
