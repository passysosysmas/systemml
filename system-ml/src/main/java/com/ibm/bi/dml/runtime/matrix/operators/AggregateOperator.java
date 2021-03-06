/**
 * (C) Copyright IBM Corp. 2010, 2015
 *
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
 * 
 */


package com.ibm.bi.dml.runtime.matrix.operators;

import java.io.Serializable;

import com.ibm.bi.dml.lops.PartialAggregate.CorrectionLocationType;
import com.ibm.bi.dml.runtime.functionobjects.KahanPlus;
import com.ibm.bi.dml.runtime.functionobjects.KahanPlusSq;
import com.ibm.bi.dml.runtime.functionobjects.Minus;
import com.ibm.bi.dml.runtime.functionobjects.Or;
import com.ibm.bi.dml.runtime.functionobjects.Plus;
import com.ibm.bi.dml.runtime.functionobjects.ValueFunction;


public class AggregateOperator  extends Operator implements Serializable
{

	private static final long serialVersionUID = 8761527329665129670L;

	public double initialValue;
	public BinaryOperator increOp;
	
	public boolean correctionExists=false;
	public CorrectionLocationType correctionLocation=CorrectionLocationType.INVALID;
	
	public AggregateOperator(double initValue, ValueFunction op)
	{
		initialValue=initValue;
		increOp=new BinaryOperator(op);
		//increFn=op;
		//as long as (v op 0)=v, then op is sparseSafe
		if(op instanceof Plus || op instanceof KahanPlus || op instanceof KahanPlusSq
				|| op instanceof Or || op instanceof Minus)
			sparseSafe=true;
		else
			sparseSafe=false;
	}
	
	public AggregateOperator(double initValue, ValueFunction op, boolean correctionExists, CorrectionLocationType correctionLocation)
	{
		this(initValue, op);
		this.correctionExists=correctionExists;
		this.correctionLocation=correctionLocation;
	}
	
}
