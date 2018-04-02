package com.defano.hypertalk.ast.statements.commands;

import com.defano.hypertalk.ast.model.specifiers.PartSpecifier;
import com.defano.wyldcard.WyldCard;
import com.defano.wyldcard.runtime.interpreter.Interpreter;
import com.defano.hypertalk.ast.expressions.Expression;
import com.defano.hypertalk.ast.statements.Command;
import com.defano.hypertalk.exception.HtException;
import org.antlr.v4.runtime.ParserRuleContext;

public class DoCmd extends Command {

    public final Expression script;
    
    public DoCmd(ParserRuleContext context, Expression script) {
        super(context, "do");
        this.script = script;
    }
    
    public void onExecute () throws HtException {
        PartSpecifier target = WyldCard.getInstance().getActiveStackDisplayedCard().getCardModel().getPartSpecifier();
        Interpreter.asyncExecuteString(target, script.evaluate().toString()).checkedGet();
    }
}
