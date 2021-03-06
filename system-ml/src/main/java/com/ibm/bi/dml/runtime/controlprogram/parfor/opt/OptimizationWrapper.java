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

package com.ibm.bi.dml.runtime.controlprogram.parfor.opt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.ibm.bi.dml.api.DMLScript;
import com.ibm.bi.dml.hops.OptimizerUtils;
import com.ibm.bi.dml.hops.ipa.InterProceduralAnalysis;
import com.ibm.bi.dml.hops.rewrite.HopRewriteRule;
import com.ibm.bi.dml.hops.rewrite.ProgramRewriteStatus;
import com.ibm.bi.dml.hops.rewrite.ProgramRewriter;
import com.ibm.bi.dml.hops.rewrite.RewriteConstantFolding;
import com.ibm.bi.dml.hops.rewrite.RewriteRemoveUnnecessaryBranches;
import com.ibm.bi.dml.hops.rewrite.StatementBlockRewriteRule;
import com.ibm.bi.dml.hops.recompile.Recompiler;
import com.ibm.bi.dml.parser.DMLProgram;
import com.ibm.bi.dml.parser.ForStatement;
import com.ibm.bi.dml.parser.ForStatementBlock;
import com.ibm.bi.dml.parser.IfStatement;
import com.ibm.bi.dml.parser.IfStatementBlock;
import com.ibm.bi.dml.parser.LanguageException;
import com.ibm.bi.dml.parser.ParForStatementBlock;
import com.ibm.bi.dml.parser.StatementBlock;
import com.ibm.bi.dml.parser.WhileStatement;
import com.ibm.bi.dml.parser.WhileStatementBlock;
import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.DMLUnsupportedOperationException;
import com.ibm.bi.dml.runtime.controlprogram.ForProgramBlock;
import com.ibm.bi.dml.runtime.controlprogram.FunctionProgramBlock;
import com.ibm.bi.dml.runtime.controlprogram.IfProgramBlock;
import com.ibm.bi.dml.runtime.controlprogram.LocalVariableMap;
import com.ibm.bi.dml.runtime.controlprogram.ParForProgramBlock;
import com.ibm.bi.dml.runtime.controlprogram.Program;
import com.ibm.bi.dml.runtime.controlprogram.ProgramBlock;
import com.ibm.bi.dml.runtime.controlprogram.WhileProgramBlock;
import com.ibm.bi.dml.runtime.controlprogram.ParForProgramBlock.POptMode;
import com.ibm.bi.dml.runtime.controlprogram.context.ExecutionContext;
import com.ibm.bi.dml.runtime.controlprogram.context.ExecutionContextFactory;
import com.ibm.bi.dml.runtime.controlprogram.parfor.opt.Optimizer.CostModelType;
import com.ibm.bi.dml.runtime.controlprogram.parfor.stat.InfrastructureAnalyzer;
import com.ibm.bi.dml.runtime.controlprogram.parfor.stat.Stat;
import com.ibm.bi.dml.runtime.controlprogram.parfor.stat.StatisticMonitor;
import com.ibm.bi.dml.runtime.controlprogram.parfor.stat.Timing;
import com.ibm.bi.dml.runtime.util.UtilFunctions;
import com.ibm.bi.dml.utils.Statistics;


/**
 * Wrapper to ParFOR cost estimation and optimizer. This is intended to be the 
 * only public access to the optimizer package.
 * 
 * NOTE: There are two main alternatives for invocation of this OptimizationWrapper:
 * (1) During compilation (after creating rtprog), (2) on execute of all top-level ParFOR PBs.
 * We decided to use (2) (and carry the SBs during execution) due to the following advantages
 *   - Known Statistics: problem size of top-level parfor known, in general, less unknown statistics
 *   - No Overhead: preventing overhead for non-parfor scripts (finding top-level parfors)
 *   - Simplicity: no need of finding top-level parfors 
 * 
 */
public class OptimizationWrapper 
{
	
	private static final boolean LDEBUG = false; //internal local debug level
	private static final Log LOG = LogFactory.getLog(OptimizationWrapper.class.getName());
	
	//internal parameters
	public static final double PAR_FACTOR_INFRASTRUCTURE = 1.0;
	private static final boolean ALLOW_RUNTIME_COSTMODEL = false;
	private static final boolean CHECK_PLAN_CORRECTNESS = false; 
	
	static
	{
		// for internal debugging only
		if( LDEBUG ) {
			Logger.getLogger("com.ibm.bi.dml.runtime.controlprogram.parfor.opt")
				  .setLevel((Level) Level.DEBUG);
		}
	}
	
	/**
	 * Called once per DML script (during program compile time) 
	 * in order to optimize all top-level parfor program blocks.
	 * 
	 * NOTE: currently note used at all.
	 * 
	 * @param prog
	 * @param rtprog
	 * @throws DMLRuntimeException 
	 * @throws LanguageException 
	 * @throws DMLUnsupportedOperationException 
	 */
	public static void optimize(DMLProgram prog, Program rtprog, boolean monitor) 
		throws DMLRuntimeException, LanguageException, DMLUnsupportedOperationException 
	{
		LOG.debug("ParFOR Opt: Running optimize all on DML program "+DMLScript.getUUID());
		
		//init internal structures 
		HashMap<Long, ParForStatementBlock> sbs = new HashMap<Long, ParForStatementBlock>();
		HashMap<Long, ParForProgramBlock> pbs = new HashMap<Long, ParForProgramBlock>();	
		
		//find all top-level paror pbs
		findParForProgramBlocks(prog, rtprog, sbs, pbs);
		
		// Create an empty symbol table
		ExecutionContext ec = ExecutionContextFactory.createContext();
		
		//optimize each top-level parfor pb independently
		for( Entry<Long, ParForProgramBlock> entry : pbs.entrySet() )
		{
			long key = entry.getKey();
			ParForStatementBlock sb = sbs.get(key);
			ParForProgramBlock pb = entry.getValue();
			
			//optimize (and implicit exchange)
			POptMode type = pb.getOptimizationMode(); //known to be >0
			optimize( type, sb, pb, ec, monitor );
		}		
		
		LOG.debug("ParFOR Opt: Finished optimization for DML program "+DMLScript.getUUID());
	}

	/**
	 * Called once per top-level parfor (during runtime, on parfor execute)
	 * in order to optimize the specific parfor program block.
	 * 
	 * NOTE: this is the default way to invoke parfor optimizers.
	 * 
	 * @param type
	 * @param sb
	 * @param pb
	 * @throws DMLRuntimeException
	 * @throws DMLUnsupportedOperationException 
	 */
	public static void optimize( POptMode type, ParForStatementBlock sb, ParForProgramBlock pb, ExecutionContext ec, boolean monitor ) 
		throws DMLRuntimeException, DMLUnsupportedOperationException
	{
		Timing time = new Timing(true);
		
		LOG.debug("ParFOR Opt: Running optimization for ParFOR("+pb.getID()+")");
		
		
		//set max contraints if not specified
		int ck = UtilFunctions.toInt( Math.max( InfrastructureAnalyzer.getCkMaxCP(),
						                        InfrastructureAnalyzer.getCkMaxMR() ) * PAR_FACTOR_INFRASTRUCTURE );
		double cm = InfrastructureAnalyzer.getCmMax() * OptimizerUtils.MEM_UTIL_FACTOR; 
		
		//execute optimizer
		optimize( type, ck, cm, sb, pb, ec, monitor );
		
		double timeVal = time.stop();
		LOG.debug("ParFOR Opt: Finished optimization for PARFOR("+pb.getID()+") in "+timeVal+"ms.");
		//System.out.println("ParFOR Opt: Finished optimization for PARFOR("+pb.getID()+") in "+timeVal+"ms.");
		if( monitor )
			StatisticMonitor.putPFStat( pb.getID() , Stat.OPT_T, timeVal);
	}
	
	/**
	 * 
	 * @param optLogLevel
	 */
	public static void setLogLevel( Level optLogLevel )
	{
		if( !LDEBUG ){ //set log level if not overwritten by internal flag
			Logger.getLogger("com.ibm.bi.dml.runtime.controlprogram.parfor.opt")
			      .setLevel( optLogLevel );
		}
	}
	
	/**
	 * 
	 * @param type
	 * @param ck
	 * @param cm
	 * @param sb
	 * @param pb
	 * @throws DMLRuntimeException
	 * @throws DMLUnsupportedOperationException 
	 * @throws  
	 */
	@SuppressWarnings("unused")
	private static void optimize( POptMode otype, int ck, double cm, ParForStatementBlock sb, ParForProgramBlock pb, ExecutionContext ec, boolean monitor ) 
		throws DMLRuntimeException, DMLUnsupportedOperationException
	{
		Timing time = new Timing(true);
		
		//maintain statistics
		if( DMLScript.STATISTICS )
			Statistics.incrementParForOptimCount();
		
		//create specified optimizer
		Optimizer opt = createOptimizer( otype );
		CostModelType cmtype = opt.getCostModelType();
		LOG.trace("ParFOR Opt: Created optimizer ("+otype+","+opt.getPlanInputType()+","+opt.getCostModelType());
		
		if( cmtype == CostModelType.RUNTIME_METRICS  //TODO remove check when perftesttool supported
			&& !ALLOW_RUNTIME_COSTMODEL )
		{
			throw new DMLRuntimeException("ParFOR Optimizer "+otype+" requires cost model "+cmtype+" that is not suported yet.");
		}
		
		OptTree tree = null;
		
		//recompile parfor body 
		if( OptimizerUtils.ALLOW_DYN_RECOMPILATION )
		{
			ForStatement fs = (ForStatement) sb.getStatement(0);
			
			//debug output before recompilation
			if( LOG.isDebugEnabled() ) 
			{
				try {
					tree = OptTreeConverter.createOptTree(ck, cm, opt.getPlanInputType(), sb, pb, ec); 
					LOG.debug("ParFOR Opt: Input plan (before recompilation):\n" + tree.explain(false));
					OptTreeConverter.clear();
				}
				catch(Exception ex)
				{
					throw new DMLRuntimeException("Unable to create opt tree.", ex);
				}
			}
			
			//constant propagation into parfor body 
			//(input scalars to parfor are guaranteed read only, but need to ensure safe-replace on multiple reopt
			//separate propagation required because recompile in-place without literal replacement)
			try{
				LocalVariableMap constVars = ProgramRecompiler.getReusableScalarVariables(sb.getDMLProg(), sb, ec.getVariables());
				ProgramRecompiler.replaceConstantScalarVariables(sb, constVars);
			}
			catch(Exception ex){
				throw new DMLRuntimeException(ex);
			}
			
			
			//program rewrites (e.g., constant folding, branch removal) according to replaced literals
			try {
				ProgramRewriter rewriter = createProgramRewriterWithRuleSets();
				ProgramRewriteStatus state = new ProgramRewriteStatus();
				rewriter.rewriteStatementBlockHopDAGs( sb, state );
				fs.setBody(rewriter.rewriteStatementBlocks(fs.getBody(), state));
				if( state.getRemovedBranches() ){
					LOG.debug("ParFOR Opt: Removed branches during program rewrites, rebuilding runtime program");
					pb.setChildBlocks(ProgramRecompiler.generatePartitialRuntimeProgram(pb.getProgram(), fs.getBody()));
				}
			}
			catch(Exception ex){
				throw new DMLRuntimeException(ex);
			}
			
			//recompilation of parfor body and called functions (if safe)
			try{
				//core parfor body recompilation (based on symbol table entries)
				//* clone of variables in order to allow for statistics propagation across DAGs
				//(tid=0, because deep copies created after opt)
				LocalVariableMap tmp = (LocalVariableMap) ec.getVariables().clone();
				Recompiler.recompileProgramBlockHierarchy(pb.getChildBlocks(), tmp, 0, true);
				
				//inter-procedural optimization (based on previous recompilation)
				if( pb.hasFunctions() ) {
					InterProceduralAnalysis ipa = new InterProceduralAnalysis();
					Set<String> fcand = ipa.analyzeSubProgram(sb);		
					
					if( !fcand.isEmpty() ) {
						//regenerate runtime program of modified functions
						for( String func : fcand )
						{
							String[] funcparts = DMLProgram.splitFunctionKey(func);
							FunctionProgramBlock fpb = pb.getProgram().getFunctionProgramBlock(funcparts[0], funcparts[1]);
							//reset recompilation flags according to recompileOnce because it is only safe if function is recompileOnce 
							//because then recompiled for every execution (otherwise potential issues if func also called outside parfor)
							Recompiler.recompileProgramBlockHierarchy(fpb.getChildBlocks(), new LocalVariableMap(), 0, fpb.isRecompileOnce());
						}		
					}
				}
			}
			catch(Exception ex){
				throw new DMLRuntimeException(ex);
			}
		}
		
		//create opt tree (before optimization)
		try {
			tree = OptTreeConverter.createOptTree(ck, cm, opt.getPlanInputType(), sb, pb, ec); 
			LOG.debug("ParFOR Opt: Input plan (before optimization):\n" + tree.explain(false));
		}
		catch(Exception ex)
		{
			throw new DMLRuntimeException("Unable to create opt tree.", ex);
		}
		
		//create cost estimator
		CostEstimator est = createCostEstimator( cmtype );
		LOG.trace("ParFOR Opt: Created cost estimator ("+cmtype+")");
		
		//core optimize
		opt.optimize( sb, pb, tree, est, ec );
		LOG.debug("ParFOR Opt: Optimized plan (after optimization): \n" + tree.explain(false));
		
		//assert plan correctness
		if( CHECK_PLAN_CORRECTNESS && LOG.isDebugEnabled() )
		{
			try{
				OptTreePlanChecker.checkProgramCorrectness(pb, sb, new HashSet<String>());
				LOG.debug("ParFOR Opt: Checked plan and program correctness.");
			}
			catch(Exception ex)
			{
				throw new DMLRuntimeException("Failed to check program correctness.", ex);
			}
		}
		
		long ltime = (long) time.stop();
		LOG.trace("ParFOR Opt: Optimized plan in "+ltime+"ms.");
		if( DMLScript.STATISTICS )
			Statistics.incrementParForOptimTime(ltime);
		
		//cleanup phase
		OptTreeConverter.clear();
		
		//monitor stats
		if( monitor )
		{
			StatisticMonitor.putPFStat( pb.getID() , Stat.OPT_OPTIMIZER, otype.ordinal());
			StatisticMonitor.putPFStat( pb.getID() , Stat.OPT_NUMTPLANS, opt.getNumTotalPlans());
			StatisticMonitor.putPFStat( pb.getID() , Stat.OPT_NUMEPLANS, opt.getNumEvaluatedPlans());
		}
	}

	/**
	 * 
	 * @param prog
	 * @param rtprog
	 * @throws LanguageException 
	 */
	private static void findParForProgramBlocks( DMLProgram prog, Program rtprog, 
			HashMap<Long, ParForStatementBlock> sbs, HashMap<Long, ParForProgramBlock> pbs ) 
		throws LanguageException
	{
		//handle function program blocks
		HashMap<String,FunctionProgramBlock> fpbs = rtprog.getFunctionProgramBlocks();
		for( Entry<String, FunctionProgramBlock> entry : fpbs.entrySet() )
		{
			String[] keypart = entry.getKey().split( Program.KEY_DELIM );
			String namespace = keypart[0];
			String name      = keypart[1]; 
			
			ProgramBlock pb = entry.getValue();
			StatementBlock sb = prog.getFunctionStatementBlock(namespace, name);
			
			//recursive find 
			rfindParForProgramBlocks(sb, pb, sbs, pbs);	
		}
		
		//handle actual program blocks
		ArrayList<ProgramBlock> tpbs = rtprog.getProgramBlocks();
		for( int i=0; i<tpbs.size(); i++ )
		{
			ProgramBlock pb = tpbs.get(i);
			StatementBlock sb = prog.getStatementBlock(i);
			
			//recursive find
			rfindParForProgramBlocks(sb, pb, sbs, pbs);
		}	
	}
	
	/**
	 * 
	 * @param sb
	 * @param pb
	 */
	private static void rfindParForProgramBlocks( StatementBlock sb, ProgramBlock pb,
			HashMap<Long, ParForStatementBlock> sbs, HashMap<Long, ParForProgramBlock> pbs )
	{
		if( pb instanceof ParForProgramBlock  ) 
		{
			//put top-level parfor into map, but no recursion
			ParForProgramBlock pfpb = (ParForProgramBlock) pb;
			ParForStatementBlock pfsb = (ParForStatementBlock) sb;
			
			LOG.trace("ParFOR: found ParForProgramBlock with POptMode="+pfpb.getOptimizationMode().toString());
			
			if( pfpb.getOptimizationMode() != POptMode.NONE )
			{
				//register programblock tree for optimization
				long pfid = pfpb.getID();
				pbs.put(pfid, pfpb);
				sbs.put(pfid, pfsb);
			}
		}
		else if( pb instanceof ForProgramBlock )
		{
			//recursive find
			ArrayList<ProgramBlock> fpbs = ((ForProgramBlock) pb).getChildBlocks();
			ArrayList<StatementBlock> fsbs = ((ForStatement)((ForStatementBlock) sb).getStatement(0)).getBody();
			for( int i=0;  i< fpbs.size(); i++ )
				rfindParForProgramBlocks(fsbs.get(i), fpbs.get(i), sbs, pbs);
		}
		else if( pb instanceof WhileProgramBlock )
		{
			//recursive find
			ArrayList<ProgramBlock> wpbs = ((WhileProgramBlock) pb).getChildBlocks();
			ArrayList<StatementBlock> wsbs = ((WhileStatement)((WhileStatementBlock) sb).getStatement(0)).getBody();
			for( int i=0;  i< wpbs.size(); i++ )
				rfindParForProgramBlocks(wsbs.get(i), wpbs.get(i), sbs, pbs);	
		}
		else if( pb instanceof IfProgramBlock  )
		{
			//recursive find
			IfProgramBlock ifpb = (IfProgramBlock) pb;
			IfStatement ifs = (IfStatement) ((IfStatementBlock) sb).getStatement(0);			
			ArrayList<ProgramBlock> ipbs1 = ifpb.getChildBlocksIfBody();
			ArrayList<ProgramBlock> ipbs2 = ifpb.getChildBlocksElseBody();
			ArrayList<StatementBlock> isbs1 = ifs.getIfBody();
			ArrayList<StatementBlock> isbs2 = ifs.getElseBody();			
			for( int i=0;  i< ipbs1.size(); i++ )
				rfindParForProgramBlocks(isbs1.get(i), ipbs1.get(i), sbs, pbs);				
			for( int i=0;  i< ipbs2.size(); i++ )
				rfindParForProgramBlocks(isbs2.get(i), ipbs2.get(i), sbs, pbs);								
		}
	}
	
	/**
	 * 
	 * @param otype
	 * @return
	 * @throws DMLRuntimeException
	 */
	private static Optimizer createOptimizer( POptMode otype ) 
		throws DMLRuntimeException
	{
		Optimizer opt = null;
		
		switch( otype )
		{
			case HEURISTIC:
				opt = new OptimizerHeuristic();
				break;
			case RULEBASED:
				opt = new OptimizerRuleBased();
				break;	
			case CONSTRAINED:
				opt = new OptimizerConstrained();
				break;	
		
			//MB: removed unused and experimental prototypes
			//case FULL_DP:
			//	opt = new OptimizerDPEnum();
			//	break;
			//case GREEDY:
			//	opt = new OptimizerGreedyEnum();
			//	break;
			
			default:
				throw new DMLRuntimeException("Undefined optimizer: '"+otype+"'.");
		}
		
		return opt;
	}

	/**
	 * 
	 * @param cmtype
	 * @return
	 * @throws DMLRuntimeException
	 */
	private static CostEstimator createCostEstimator( CostModelType cmtype ) 
		throws DMLRuntimeException
	{
		CostEstimator est = null;
		
		switch( cmtype )
		{
			case STATIC_MEM_METRIC:
				est = new CostEstimatorHops( OptTreeConverter.getAbstractPlanMapping() );
				break;
			case RUNTIME_METRICS:
				est = new CostEstimatorRuntime();
				break;
			default:
				throw new DMLRuntimeException("Undefined cost model type: '"+cmtype+"'.");
		}
		
		return est;
	}
	
	/**
	 * 
	 * @return
	 */
	private static ProgramRewriter createProgramRewriterWithRuleSets()
	{
		//create hop rewrite set
		ArrayList<HopRewriteRule> hRewrites = new ArrayList<HopRewriteRule>();
		hRewrites.add( new RewriteConstantFolding() );
		
		//create statementblock rewrite set
		ArrayList<StatementBlockRewriteRule> sbRewrites = new ArrayList<StatementBlockRewriteRule>();
		sbRewrites.add( new RewriteRemoveUnnecessaryBranches() );
		
		ProgramRewriter rewriter = new ProgramRewriter( hRewrites, sbRewrites );
		
		return rewriter;
	}
}
