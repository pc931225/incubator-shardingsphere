/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.core.parse.antlr.extractor.impl.dml;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import org.antlr.v4.runtime.ParserRuleContext;
import org.apache.shardingsphere.core.constant.ShardingOperator;
import org.apache.shardingsphere.core.parse.antlr.constant.LogicalOperator;
import org.apache.shardingsphere.core.parse.antlr.constant.Paren;
import org.apache.shardingsphere.core.parse.antlr.extractor.api.OptionalSQLSegmentExtractor;
import org.apache.shardingsphere.core.parse.antlr.extractor.impl.common.column.ColumnExtractor;
import org.apache.shardingsphere.core.parse.antlr.extractor.util.ExtractorUtils;
import org.apache.shardingsphere.core.parse.antlr.extractor.util.RuleName;
import org.apache.shardingsphere.core.parse.antlr.sql.segment.dml.column.ColumnSegment;
import org.apache.shardingsphere.core.parse.antlr.sql.segment.dml.condition.AndPredicateSegment;
import org.apache.shardingsphere.core.parse.antlr.sql.segment.dml.condition.OrPredicateSegment;
import org.apache.shardingsphere.core.parse.antlr.sql.segment.dml.condition.PredicateSegment;
import org.apache.shardingsphere.core.parse.antlr.sql.segment.dml.expr.BetweenValueExpressionSegment;
import org.apache.shardingsphere.core.parse.antlr.sql.segment.dml.expr.CompareValueExpressionSegment;
import org.apache.shardingsphere.core.parse.antlr.sql.segment.dml.expr.ExpressionSegment;
import org.apache.shardingsphere.core.parse.antlr.sql.segment.dml.expr.InValueExpressionSegment;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Predicate extractor.
 *
 * @author duhongjun
 */
public final class PredicateExtractor implements OptionalSQLSegmentExtractor {
    
    private final ExpressionExtractor expressionExtractor = new ExpressionExtractor();
    
    private final ColumnExtractor columnExtractor = new ColumnExtractor();
    
    @Override
    public Optional<OrPredicateSegment> extract(final ParserRuleContext exprNode, final Map<ParserRuleContext, Integer> parameterMarkerIndexes) {
        return extractConditionInternal(exprNode, parameterMarkerIndexes);
    }
    
    private Optional<OrPredicateSegment> extractConditionInternal(final ParserRuleContext exprNode, final Map<ParserRuleContext, Integer> parameterMarkerIndexes) {
        Optional<Integer> index = getLogicalOperatorIndex(exprNode);
        if (!index.isPresent()) {
            return extractConditionForParen(exprNode, parameterMarkerIndexes);
        }
        Optional<OrPredicateSegment> leftOrCondition = extractConditionInternal((ParserRuleContext) exprNode.getChild(index.get() - 1), parameterMarkerIndexes);
        Optional<OrPredicateSegment> rightOrCondition = extractConditionInternal((ParserRuleContext) exprNode.getChild(index.get() + 1), parameterMarkerIndexes);
        if (leftOrCondition.isPresent() && rightOrCondition.isPresent()) {
            return Optional.of(mergePredicate(leftOrCondition.get(), rightOrCondition.get(), exprNode.getChild(index.get()).getText()));
        }
        return leftOrCondition.isPresent() ? leftOrCondition : rightOrCondition;
    }
    
    private Optional<Integer> getLogicalOperatorIndex(final ParserRuleContext exprNode) {
        for (int i = 0; i < exprNode.getChildCount(); i++) {
            if (LogicalOperator.isLogicalOperator(exprNode.getChild(i).getText())) {
                return Optional.of(i);
            }
        }
        return Optional.absent();
    }
    
    private Optional<OrPredicateSegment> extractConditionForParen(final ParserRuleContext exprNode, final Map<ParserRuleContext, Integer> parameterMarkerIndexes) {
        Optional<Integer> index = getLeftParenIndex(exprNode);
        if (index.isPresent()) {
            if (RuleName.EXPR.getName().equals(exprNode.getChild(index.get() + 1).getClass().getSimpleName())) {
                return extractConditionInternal((ParserRuleContext) exprNode.getChild(index.get() + 1), parameterMarkerIndexes);
            }
            return Optional.absent();
        }
        Optional<PredicateSegment> predicate = extractPredicate(exprNode, parameterMarkerIndexes);
        if (!predicate.isPresent()) {
            return Optional.absent();
        }
        OrPredicateSegment result = new OrPredicateSegment();
        AndPredicateSegment newAndPredicate = new AndPredicateSegment();
        newAndPredicate.getPredicates().add(predicate.get());
        result.getAndPredicates().add(newAndPredicate);
        return Optional.of(result);
    }
    
    private Optional<Integer> getLeftParenIndex(final ParserRuleContext exprNode) {
        for (int i = 0; i < exprNode.getChildCount(); i++) {
            if (Paren.isLeftParen(exprNode.getChild(i).getText())) {
                return Optional.of(i);
            }
        }
        return Optional.absent();
    }
    
    private Optional<PredicateSegment> extractPredicate(final ParserRuleContext exprNode, final Map<ParserRuleContext, Integer> parameterMarkerIndexes) {
        Optional<PredicateSegment> result = extractComparisonPredicate(exprNode, parameterMarkerIndexes);
        if (result.isPresent()) {
            return result;
        }
        Optional<ParserRuleContext> predicateNode = ExtractorUtils.findFirstChildNode(exprNode, RuleName.PREDICATE);
        if (!predicateNode.isPresent()) {
            return Optional.absent();
        }
        Optional<ColumnSegment> column = columnExtractor.extract((ParserRuleContext) predicateNode.get().getChild(0), parameterMarkerIndexes);
        if (!column.isPresent()) {
            return Optional.absent();
        }
        if (5 == predicateNode.get().getChildCount() && "BETWEEN".equalsIgnoreCase(predicateNode.get().getChild(1).getText())) {
            result = extractBetweenPredicate(predicateNode.get(), parameterMarkerIndexes, column.get());
            if (result.isPresent()) {
                return result;
            }
        }
        if (predicateNode.get().getChildCount() >= 5 && "IN".equalsIgnoreCase(predicateNode.get().getChild(1).getText())) {
            result = extractInPredicate(predicateNode.get(), parameterMarkerIndexes, column.get());
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.absent();
    }
    
    private Optional<PredicateSegment> extractComparisonPredicate(final ParserRuleContext exprNode, final Map<ParserRuleContext, Integer> parameterMarkerIndexes) {
        Optional<ParserRuleContext> comparisonOperatorNode = ExtractorUtils.findFirstChildNode(exprNode, RuleName.COMPARISON_OPERATOR);
        if (!comparisonOperatorNode.isPresent()) {
            return Optional.absent();
        }
        ParserRuleContext booleanPrimaryNode = comparisonOperatorNode.get().getParent();
        Optional<ParserRuleContext> leftColumnNode = ExtractorUtils.findFirstChildNode((ParserRuleContext) booleanPrimaryNode.getChild(0), RuleName.COLUMN_NAME);
        Optional<ParserRuleContext> rightColumnNode = ExtractorUtils.findFirstChildNode((ParserRuleContext) booleanPrimaryNode.getChild(2), RuleName.COLUMN_NAME);
        if (leftColumnNode.isPresent() && rightColumnNode.isPresent() || !leftColumnNode.isPresent() && !rightColumnNode.isPresent()) {
            return Optional.absent();
        }
        Optional<ColumnSegment> column = columnExtractor.extract(exprNode, parameterMarkerIndexes);
        Preconditions.checkState(column.isPresent());
        ParserRuleContext valueNode = leftColumnNode.isPresent()
                ? (ParserRuleContext) comparisonOperatorNode.get().parent.getChild(2) : (ParserRuleContext) comparisonOperatorNode.get().parent.getChild(0);
        Optional<? extends ExpressionSegment> sqlExpression = expressionExtractor.extract(valueNode, parameterMarkerIndexes);
        String compareOperator = comparisonOperatorNode.get().getText();
        return sqlExpression.isPresent() ? Optional.of(new PredicateSegment(column.get(), compareOperator, 
                new CompareValueExpressionSegment(sqlExpression.get(), compareOperator), booleanPrimaryNode.getStop().getStopIndex())) : Optional.<PredicateSegment>absent();
    }
    
    private Optional<PredicateSegment> extractBetweenPredicate(final ParserRuleContext predicateNode, final Map<ParserRuleContext, Integer> parameterMarkerIndexes, final ColumnSegment column) {
        Optional<? extends ExpressionSegment> beginSQLExpression = expressionExtractor.extract((ParserRuleContext) predicateNode.getChild(2), parameterMarkerIndexes);
        Optional<? extends ExpressionSegment> endSQLExpression = expressionExtractor.extract((ParserRuleContext) predicateNode.getChild(4), parameterMarkerIndexes);
        return beginSQLExpression.isPresent() && endSQLExpression.isPresent()
                ? Optional.of(new PredicateSegment(
                        column, ShardingOperator.BETWEEN.name(), new BetweenValueExpressionSegment(beginSQLExpression.get(), endSQLExpression.get()), predicateNode.getStop().getStopIndex()))
                : Optional.<PredicateSegment>absent();
    }
    
    private Optional<PredicateSegment> extractInPredicate(final ParserRuleContext predicateNode, final Map<ParserRuleContext, Integer> parameterMarkerIndexes, final ColumnSegment column) {
        Collection<ExpressionSegment> sqlExpressions = extractExpressionSegments(predicateNode, parameterMarkerIndexes);
        return sqlExpressions.isEmpty() ? Optional.<PredicateSegment>absent()
                : Optional.of(new PredicateSegment(column, ShardingOperator.IN.name(), new InValueExpressionSegment(sqlExpressions), predicateNode.getStop().getStopIndex()));
    }
    
    private Collection<ExpressionSegment> extractExpressionSegments(final ParserRuleContext predicateNode, final Map<ParserRuleContext, Integer> parameterMarkerIndexes) {
        List<ExpressionSegment> result = new LinkedList<>();
        for (int i = 3; i < predicateNode.getChildCount(); i++) {
            if (RuleName.EXPR.getName().equals(predicateNode.getChild(i).getClass().getSimpleName())) {
                Optional<? extends ExpressionSegment> expression = expressionExtractor.extract((ParserRuleContext) predicateNode.getChild(i), parameterMarkerIndexes);
                // FIXME if some part of expr is not supported, clear all expr for IN clause
                if (!expression.isPresent()) {
                    return Collections.emptyList();
                }
                result.add(expression.get());
            }
        }
        return result;
    }
    
    private OrPredicateSegment mergePredicate(final OrPredicateSegment leftOrPredicate, final OrPredicateSegment rightOrPredicate, final String operator) {
        if (LogicalOperator.isOrOperator(operator)) {
            leftOrPredicate.getAndPredicates().addAll(rightOrPredicate.getAndPredicates());
            return leftOrPredicate;
        }
        OrPredicateSegment result = new OrPredicateSegment();
        for (AndPredicateSegment each : leftOrPredicate.getAndPredicates()) {
            for (AndPredicateSegment eachRightOr : rightOrPredicate.getAndPredicates()) {
                AndPredicateSegment tempList = new AndPredicateSegment();
                tempList.getPredicates().addAll(each.getPredicates());
                tempList.getPredicates().addAll(eachRightOr.getPredicates());
                result.getAndPredicates().add(tempList);
            }
        }
        return result;
    }
}
