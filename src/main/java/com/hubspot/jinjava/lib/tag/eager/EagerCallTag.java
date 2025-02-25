package com.hubspot.jinjava.lib.tag.eager;

import com.hubspot.jinjava.interpret.DeferredMacroValueImpl;
import com.hubspot.jinjava.interpret.InterpretException;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.interpret.JinjavaInterpreter.InterpreterScopeClosable;
import com.hubspot.jinjava.lib.expression.EagerExpressionStrategy;
import com.hubspot.jinjava.lib.fn.MacroFunction;
import com.hubspot.jinjava.lib.tag.CallTag;
import com.hubspot.jinjava.lib.tag.FlexibleTag;
import com.hubspot.jinjava.tree.TagNode;
import com.hubspot.jinjava.tree.parse.ExpressionToken;
import com.hubspot.jinjava.tree.parse.TagToken;
import com.hubspot.jinjava.util.EagerExpressionResolver;
import com.hubspot.jinjava.util.EagerExpressionResolver.EagerExpressionResult;
import com.hubspot.jinjava.util.EagerReconstructionUtils;
import com.hubspot.jinjava.util.EagerReconstructionUtils.EagerChildContextConfig;
import com.hubspot.jinjava.util.LengthLimitingStringJoiner;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

public class EagerCallTag extends EagerStateChangingTag<CallTag> {

  public EagerCallTag() {
    super(new CallTag());
  }

  public EagerCallTag(CallTag tag) {
    super(tag);
  }

  @Override
  public String eagerInterpret(
    TagNode tagNode,
    JinjavaInterpreter interpreter,
    InterpretException e
  ) {
    interpreter.getContext().checkNumberOfDeferredTokens();
    try (InterpreterScopeClosable c = interpreter.enterScope()) {
      MacroFunction caller = new MacroFunction(
        tagNode.getChildren(),
        "caller",
        new LinkedHashMap<>(),
        true,
        interpreter.getContext(),
        interpreter.getLineNumber(),
        interpreter.getPosition()
      );
      interpreter.getContext().addGlobalMacro(caller);
      EagerExecutionResult eagerExecutionResult = EagerReconstructionUtils.executeInChildContext(
        eagerInterpreter ->
          EagerExpressionResolver.resolveExpression(
            tagNode.getHelpers().trim(),
            interpreter
          ),
        interpreter,
        EagerChildContextConfig
          .newBuilder()
          .withTakeNewValue(true)
          .withPartialMacroEvaluation(
            interpreter.getConfig().isNestedInterpretationEnabled()
          )
          .withCheckForContextChanges(interpreter.getContext().isDeferredExecutionMode())
          .build()
      );
      StringBuilder prefixToPreserveState = new StringBuilder();
      if (interpreter.getContext().isDeferredExecutionMode()) {
        prefixToPreserveState.append(eagerExecutionResult.getPrefixToPreserveState());
      } else {
        interpreter.getContext().putAll(eagerExecutionResult.getSpeculativeBindings());
      }
      if (eagerExecutionResult.getResult().isFullyResolved()) {
        // Possible macro/set tag in front of this one.
        return (
          prefixToPreserveState.toString() +
          EagerExpressionStrategy.postProcessResult(
            new ExpressionToken(
              tagNode.getHelpers(),
              tagNode.getLineNumber(),
              tagNode.getStartPosition(),
              tagNode.getSymbols()
            ),
            eagerExecutionResult.getResult().toString(true),
            interpreter
          )
        );
      }
      caller.setDeferred(true);
      prefixToPreserveState.append(
        EagerReconstructionUtils.reconstructFromContextBeforeDeferring(
          eagerExecutionResult.getResult().getDeferredWords(),
          interpreter
        )
      );

      LengthLimitingStringJoiner joiner = new LengthLimitingStringJoiner(
        interpreter.getConfig().getMaxOutputSize(),
        " "
      );
      joiner
        .add(tagNode.getSymbols().getExpressionStartWithTag())
        .add(tagNode.getTag().getName())
        .add(eagerExecutionResult.getResult().toString().trim())
        .add(tagNode.getSymbols().getExpressionEndWithTag());
      interpreter
        .getContext()
        .handleDeferredToken(
          new DeferredToken(
            new TagToken(
              joiner.toString(),
              tagNode.getLineNumber(),
              tagNode.getStartPosition(),
              tagNode.getSymbols()
            ),
            eagerExecutionResult
              .getResult()
              .getDeferredWords()
              .stream()
              .filter(
                word ->
                  !(interpreter.getContext().get(word) instanceof DeferredMacroValueImpl)
              )
              .collect(Collectors.toSet())
          )
        );
      StringBuilder result = new StringBuilder(
        prefixToPreserveState.toString() + joiner.toString()
      );
      if (!tagNode.getChildren().isEmpty()) {
        result.append(
          EagerReconstructionUtils
            .executeInChildContext(
              eagerInterpreter ->
                EagerExpressionResult.fromString(
                  renderChildren(tagNode, eagerInterpreter)
                ),
              interpreter,
              EagerChildContextConfig
                .newBuilder()
                .withCheckForContextChanges(true)
                .withForceDeferredExecutionMode(true)
                .build()
            )
            .asTemplateString()
        );
      }
      if (
        StringUtils.isNotBlank(tagNode.getEndName()) &&
        (
          !(getTag() instanceof FlexibleTag) ||
          ((FlexibleTag) getTag()).hasEndTag((TagToken) tagNode.getMaster())
        )
      ) {
        result.append(EagerReconstructionUtils.reconstructEnd(tagNode));
      } // Possible set tag in front of this one.
      return EagerReconstructionUtils.wrapInAutoEscapeIfNeeded(
        result.toString(),
        interpreter
      );
    }
  }
}
