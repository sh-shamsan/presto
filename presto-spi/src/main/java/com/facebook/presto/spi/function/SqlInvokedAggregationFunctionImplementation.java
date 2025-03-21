/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.spi.function;

import com.facebook.presto.common.type.Type;
import com.facebook.presto.spi.api.Experimental;
import com.facebook.presto.spi.function.aggregation.Accumulator;
import com.facebook.presto.spi.function.aggregation.AggregationMetadata;
import com.facebook.presto.spi.function.aggregation.GroupedAccumulator;

import java.util.List;

import static java.util.Objects.requireNonNull;

//  A no-op implementation of JavaAggregationFunctionImplementation.
//  A hacky way to handle lambda aggregate functions which require an implementation of JavaAggregationFunctionImplementation.
@Experimental
public class SqlInvokedAggregationFunctionImplementation
        implements JavaAggregationFunctionImplementation
{
    private final Type intermediateType;

    private final Type finalType;

    private final boolean isOrderSensitive;

    private final List<Type> parameterTypes;

    public SqlInvokedAggregationFunctionImplementation(
            Type intermediateType,
            Type finalType,
            boolean isOrderSensitive,
            List<Type> parameterTypes)
    {
        this.intermediateType = requireNonNull(intermediateType, "intermediateType is null");
        this.finalType = requireNonNull(finalType, "finalType is null");
        this.isOrderSensitive = isOrderSensitive;
        this.parameterTypes = requireNonNull(parameterTypes, "parameterTypes is null");
    }

    @Override
    public Type getIntermediateType()
    {
        return intermediateType;
    }

    @Override
    public Type getFinalType()
    {
        return finalType;
    }

    @Override
    public boolean isDecomposable()
    {
        // This class assumes the aggregation function is decomposable.
        return true;
    }

    @Override
    public boolean isOrderSensitive()
    {
        return isOrderSensitive;
    }

    @Override
    public List<Type> getParameterTypes()
    {
        return parameterTypes;
    }

    @Override
    public AggregationMetadata getAggregationMetadata()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Class<? extends Accumulator> getAccumulatorClass()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Class<? extends GroupedAccumulator> getGroupedAccumulatorClass()
    {
        throw new UnsupportedOperationException();
    }
}
