package cn.mgtown.fmwar.game;

/** 对局状态机的阶段。 */
public enum GamePhase {
    /** 空闲：等待玩家进入队列。 */
    IDLE,
    /** 准备阶段：玩家在准备房间连点准备按钮，倒计时结束后开局。 */
    PREPARING,
    /** 对局中：倒计时、奖励箱、商店、观战都在此阶段。 */
    RUNNING,
    /** 结束中：结算清场，通常只持续若干 tick。 */
    ENDING
}
