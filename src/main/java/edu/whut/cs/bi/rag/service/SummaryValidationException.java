package edu.whut.cs.bi.rag.service;

/** 模型已响应，但内容未满足承重小结的完整性要求。 */
public class SummaryValidationException extends RuntimeException {
    public SummaryValidationException(String message) { super(message); }
}
