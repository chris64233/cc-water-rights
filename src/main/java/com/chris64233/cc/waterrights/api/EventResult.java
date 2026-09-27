package com.chris64233.cc.waterrights.api;

/**
 * 申报/冲正写入结果。{@code replayed=true} 表示命中幂等重放，返回既有事件。
 */
public record EventResult(EventResponse event, boolean replayed) {
}
