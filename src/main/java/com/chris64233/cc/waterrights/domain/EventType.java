package com.chris64233.cc.waterrights.domain;

/**
 * 台账事件类型。
 */
public enum EventType {
    /** 用水申报 */
    DECLARATION,
    /** 冲正（完整抵消一次原申报） */
    REVERSAL
}
