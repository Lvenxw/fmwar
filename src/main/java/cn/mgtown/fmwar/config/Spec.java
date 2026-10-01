package cn.mgtown.fmwar.config;

import java.util.List;

/** 配置里可直接写出来的小结构。 */
public final class Spec {

    private Spec() {
    }

    /** 一个可右键的按钮：位置 + 作用半径（方块）。 */
    public record Button(String key, Position position, double radius) {
    }

    /** 配置校验中发现的问题。 */
    public record Problem(String path, String detail) {
    }

    /** 校验结果：problems 为空表示配置可用。 */
    public record Validation(List<Problem> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }

        public String describe() {
            StringBuilder builder = new StringBuilder();
            for (Problem problem : problems) {
                builder.append(problem.path()).append(" -> ").append(problem.detail()).append("; ");
            }
            return builder.toString();
        }
    }
}
