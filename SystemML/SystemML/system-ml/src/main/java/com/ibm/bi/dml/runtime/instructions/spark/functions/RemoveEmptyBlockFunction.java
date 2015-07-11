/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2015
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.runtime.instructions.spark.functions;

import org.apache.spark.api.java.function.Function;

import scala.Tuple2;

import com.ibm.bi.dml.runtime.matrix.data.MatrixBlock;
import com.ibm.bi.dml.runtime.matrix.data.MatrixIndexes;

/**
 * 
 */
public class RemoveEmptyBlockFunction implements Function<Tuple2<MatrixIndexes,MatrixBlock>, Boolean> 
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	private static final long serialVersionUID = -8856829325565589854L;

	@Override
	public Boolean call(Tuple2<MatrixIndexes, MatrixBlock> arg0)
		throws Exception 
	{
		//returns true for non-empty matrix blocks
		return !arg0._2().isEmptyBlock(false);
	}
	

}
