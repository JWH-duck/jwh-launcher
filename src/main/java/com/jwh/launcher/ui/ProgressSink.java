package com.jwh.launcher.ui;

/**
 * 下载进度回调（后台线程可调用，实现方负责切回 FX 线程）。
 * 用于把下载进度转发到主题自适应的下载进度弹窗；搜索状态不走这里，改由全局状态栏承载。
 */
public interface ProgressSink {

    /** 更新状态文案。 */
    void message(String text);

    /**
     * 更新确定进度（0..1）；传入 {@link Double#NaN} 或负值表示不确定滚动动画。
     */
    void progress(double frac);
}
