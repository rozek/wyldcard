package com.defano.hypertalk.ast.commands;

import com.defano.hypercard.runtime.context.ExecutionContext;
import com.defano.hypertalk.ast.breakpoints.TerminateHandlerBreakpoint;
import com.defano.hypertalk.ast.statements.Statement;
import com.defano.hypertalk.exception.HtException;

public class PassCmd extends Statement {

    private final String passedMessage;

    public PassCmd(String passedMessage) {
        this.passedMessage = passedMessage;
    }

    @Override
    public void execute() throws HtException, TerminateHandlerBreakpoint {
        ExecutionContext.getContext().setPassedMessage(passedMessage);
        throw new TerminateHandlerBreakpoint(passedMessage);
    }
}
