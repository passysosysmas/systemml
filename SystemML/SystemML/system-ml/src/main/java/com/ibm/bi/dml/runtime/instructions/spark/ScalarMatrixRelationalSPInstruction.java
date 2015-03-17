package com.ibm.bi.dml.runtime.instructions.spark;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.PairFunction;

import scala.Tuple2;

import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.DMLUnsupportedOperationException;
import com.ibm.bi.dml.runtime.controlprogram.context.ExecutionContext;
import com.ibm.bi.dml.runtime.controlprogram.context.SparkExecutionContext;
import com.ibm.bi.dml.runtime.instructions.cp.CPOperand;
import com.ibm.bi.dml.runtime.instructions.cp.ScalarObject;
import com.ibm.bi.dml.runtime.matrix.MatrixCharacteristics;
import com.ibm.bi.dml.runtime.matrix.data.MatrixBlock;
import com.ibm.bi.dml.runtime.matrix.data.MatrixIndexes;
import com.ibm.bi.dml.runtime.matrix.operators.Operator;
import com.ibm.bi.dml.runtime.matrix.operators.ScalarOperator;

public class ScalarMatrixRelationalSPInstruction extends RelationalBinarySPInstruction  {
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	public ScalarMatrixRelationalSPInstruction(Operator op, 
			   								   CPOperand in1, 
			   								   CPOperand in2, 
			   								   CPOperand out, 
			   								   String opcode,
			   								   String istr){
		super(op, in1, in2, out, opcode, istr);
	}

	@Override
	public void processInstruction(ExecutionContext ec) 
		throws DMLRuntimeException, DMLUnsupportedOperationException
	{	
		String opcode = getOpcode();
		if ( opcode.equalsIgnoreCase("==") || opcode.equalsIgnoreCase("!=") || opcode.equalsIgnoreCase("<")
			|| opcode.equalsIgnoreCase(">") || opcode.equalsIgnoreCase("<=") || opcode.equalsIgnoreCase(">=")) {
			
			SparkExecutionContext sec = (SparkExecutionContext)ec;
	
			// Get input RDD
			String rddVar 	= 	(input1.getDataType() == DataType.MATRIX) ? input1.getName() : input2.getName();
			JavaPairRDD<MatrixIndexes,MatrixBlock> in1 = sec.getRDDHandleForVariable( rddVar );
			
			// Get operator and scalar
			CPOperand scalar = ( input1.getDataType() == DataType.MATRIX ) ? input2 : input1;
			ScalarObject constant = (ScalarObject) ec.getScalarInput(scalar.getName(), scalar.getValueType(), scalar.isLiteral());
			ScalarOperator sc_op = (ScalarOperator) _optr;
			sc_op.setConstant(constant.getDoubleValue());
			
			//execute scalar matrix arithmetic instruction
			MatrixCharacteristics mc = sec.getMatrixCharacteristics(rddVar);
			JavaPairRDD<MatrixIndexes,MatrixBlock> out = in1.mapToPair( new RDDScalarMatrixRelationalFunction(sc_op, mc.getRowsPerBlock(), mc.getColsPerBlock()) );
			
			//put output RDD handle into symbol table
			sec.setRDDHandleForVariable(output.getName(), out);
		}
		else {
			throw new DMLRuntimeException("Unknown opcode in Instruction: " + toString());
		}
	}
	
	private static class RDDScalarMatrixRelationalFunction implements PairFunction<Tuple2<MatrixIndexes, MatrixBlock>, MatrixIndexes, MatrixBlock> 
	{
		private static final long serialVersionUID = 8197406787010296291L;

		private ScalarOperator sc_op = null;
		private int brlen; 
		private int bclen;
		
		public RDDScalarMatrixRelationalFunction(ScalarOperator sc_op, int brlen, int bclen) {
			this.sc_op = sc_op;
			this.brlen = brlen;
			this.bclen = bclen;
		}
		
		@Override
		public Tuple2<MatrixIndexes, MatrixBlock> call(Tuple2<MatrixIndexes, MatrixBlock> arg0) throws Exception {
//			MatrixBlock resultBlk = new MatrixBlock(brlen, bclen, false);
//			arg0._2.scalarOperations(sc_op, resultBlk);
			MatrixBlock resultBlk = (MatrixBlock) arg0._2.scalarOperations(sc_op, new MatrixBlock());
			
			return new Tuple2<MatrixIndexes, MatrixBlock>(arg0._1, resultBlk);
		}
		
	}
}