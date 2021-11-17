import abstraction.ProductAutomaton;
import environment.Environment;
import environment.Label;
import modules.KnownGrid;
import modules.KnownRRG;
import modules.RRG;
import modules.UnknownRRG;
import modules.learnAskExperiments.DefaultExperiment;
import net.sf.javabdd.BDD;
import net.sf.javabdd.BDD.BDDIterator;
import net.sf.javabdd.BDDFactory;
import org.jgrapht.alg.util.Pair;
import org.jgrapht.graph.DefaultEdge;
import settings.Initialize;
import settings.PlanningSettings;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;


/**
 * @author kush
 *
 */
public class Planning
{	
	RRG rrg;
	ProductAutomaton productAutomaton;
	DefaultExperiment exper;
	Environment env;
	
	BDD currentStates;
	
	int iterationNumber 	= 0;
	double initTime;
	double beginTime;
	double startTime 		= 0;
	double samplingTime 	= 0;
	double pathTime 		= 0;
	double learnTime 		= 0;
	double explTime 		= 0;
	double adviceTime 		= 0;
	double totalTime		= 0;
    boolean needNewAdvice 	= true;
    ArrayList<BDD> advice 	= null;
    float moveLength = 0.0F;
	float pathLength = 0.0F;
    List<DefaultEdge> finalPath = new ArrayList<>();

    /**
     * initialize everything
     * @throws Exception
	 * @param factory
     */
	public Planning(BDDFactory factory) throws Exception
	{
		beginTime				= System.nanoTime();
		
		Initialize initialize	= new Initialize(factory);// read the files and initialise everything
        rrg 					= initialize.getRRG();
        productAutomaton		= initialize.getProductAutomaton();
        exper					= new DefaultExperiment(productAutomaton); // learn and advce procedures
        env 					= Initialize.getEnvironment();
        
        Label label				= Environment.getLabelling();
        BDD initStateSystem		= label.getLabel(Environment.getInit());

		//stores the set of states in the abstraction we have seen till now
        currentStates			= initStateSystem.id();
        rrg.setStartingPoint(Environment.getInit());

		// and for initial state in the product automaton
        productAutomaton.setInitState(productAutomaton.getInitStates().and(initStateSystem));
        initTime 				= System.nanoTime() - beginTime;
	}
	
	/**
	 * outputs the results and times
	 * @throws Exception
	 */
	void printOutput() throws Exception 
	{


		Pair<Float, Float> length = new Pair<>(0.0f, 0.0f);
		if((boolean) PlanningSettings.get("firstExplThenPlan")) {
			length.setFirst(moveLength);
			length.setSecond(pathLength);
		}
		else{
			UnknownRRG urrg = (UnknownRRG) rrg;
			System.out.print("Storing output ... ");
			length = urrg.plotGraph(finalPath);
			System.out.println("done");
			moveLength = length.getFirst();
			pathLength = length.getSecond();
		}
		totalTime = System.nanoTime() - beginTime;

		if((boolean) PlanningSettings.get("debug")){
			System.out.print("Exporting product automaton ... ");
			productAutomaton.createDot();
			System.out.println("done");
		}

		int adviceSamples = 0;
		for( int k=0; k<10; k++ ) {
			adviceSamples += rrg.adviceSampled[k];
		}

		if((Integer) PlanningSettings.get("numberOfRuns") == 1 || (boolean) PlanningSettings.get("debug")) {
			System.out.println("\nNumber of iterations = " + iterationNumber);
			System.out.println("Total sampled points = " + rrg.totalSampledPoints);
			System.out.println("RRG size = " + rrg.totalPoints);
			System.out.println("\nMovement Length = " + length.getFirst());
			System.out.println("Remaining path Length = " + length.getSecond());
			System.out.println("Total path Length = " + (length.getFirst()+length.getSecond()));
			System.out.println("\nAdvice samples: " + Arrays.toString(rrg.adviceSampled) + " (" + adviceSamples + ')');
			System.out.print("\nTotal time taken (in ms):");
			System.out.println(totalTime / 1000000);
		}

		if((boolean) PlanningSettings.get("debug")) {
			System.out.print("\nInitialization time (in ms):");
			System.out.println((initTime) / 1000000);
			System.out.print("Sampling time (in ms):");
			System.out.println(samplingTime / 1000000);
			System.out.print("Advice time (in ms):");
			System.out.println(adviceTime / 1000000);
			System.out.print("Path checking time (in ms):");
			System.out.println(pathTime / 1000000);
			System.out.print("Learning time (in ms):");
			System.out.println(learnTime / 1000000);
			System.out.print("Exploration time (in ms):");
			System.out.println(explTime / 1000000);
			System.out.print("Time taken other than these things (in ms):");
			System.out.println((totalTime - initTime - samplingTime - adviceTime - pathTime - learnTime) / 1000000);
		}
	}
	
//	/**
//	 * check if learning is required for the set of transitions
//	 * @param transitions
//	 * @return
//	 * @throws PlanningException
//	 */
//	boolean needLearning(BDD transitions) throws PlanningException {
//		return transitions.and(productAutomaton.sampledTransitions).isZero();
//		&& ! productAutomaton.removeAllExceptPreSystemVars(transitions).equals(productAutomaton.changePostSystemVarsToPreSystemVars(productAutomaton.removeAllExceptPostSystemVars(transitions)));
//	}
	
	/**
	 * update advice if required
	 * @throws Exception
	 */
	void updateAdvice() throws Exception {
		startTime 	= System.nanoTime(); // Advice time
    	if ( needNewAdvice ) {
    		advice			= exper.getAdvice(currentStates);
    		needNewAdvice 	= false;
    	}
    	adviceTime 			+= System.nanoTime() - startTime;
	}
	
	/**
	 * sample a batch
	 * @return
	 * @throws Exception
	 */
	BDD sampleBatch(RRG rrg) throws Exception {
		startTime = System.nanoTime(); 
    	BDD transitions = rrg.sampleBatch(advice, iterationNumber);
    	samplingTime += System.nanoTime() - startTime;
    	return transitions;
	}
	
	/**
	 * call learn procedure
	 * @param transitions
	 * @throws Exception
	 */
	void learn(BDD transitions) throws Exception {
		BDD bdd = transitions;
		startTime = System.nanoTime(); 
    	BDDIterator ite = bdd.iterator(ProductAutomaton.allSystemVars());
    	while( ite.hasNext() ) 
    	{
    		bdd = (BDD) ite.next();
//    		if( needLearning(transitions) ) {
    			exper.learn(bdd);
        		currentStates = currentStates.or(productAutomaton.getSecondStateSystem(bdd));
        		needNewAdvice = true;
//        	}
    	}
    	learnTime += System.nanoTime() - startTime;
	}
	
	/**
	 * Do exploration and planning simultaneously
	 * @throws Exception
	 */
	public Object[] explAndPlanTogether() throws Exception
	{
		boolean debug 						= (boolean) PlanningSettings.get("debug");
		System.out.println("Starting planning ... ");
		UnknownRRG urrg = (UnknownRRG) rrg;
		urrg.grid.knowDiscretization(env,
				productAutomaton,
				Environment.getInit(),
				(float) PlanningSettings.get("sensingRadius"));
		boolean computePath = false;
		while( true )
        {
        	if( debug ) {
	        	if( iterationNumber == 100 ) {
//	        		urrg.grid.printDiscretization();
//	        		urrg.grid.printFrontiers();
	        		break;
	        	}
        	}
        	
        	iterationNumber++;
        	if((boolean) PlanningSettings.get("debug")) {
				System.out.println("Iter num: " + iterationNumber);
			}

        	if ((boolean) PlanningSettings.get("useAdvice") ) {
				updateAdvice();
			}

			BDD transitions = sampleBatch(urrg);
			if ( transitions == null ) {
				continue;
			}
        	if( productAutomaton.isAcceptingTransition(transitions) ) {
				computePath = true;
			}
        	learn(transitions);
        	startTime = System.nanoTime();
        	if( computePath ) {
				ArrayList<BDD> path	= productAutomaton.findAcceptingPath();
				if( path != null ) {
					ArrayList<BDD> remainingPath = urrg.findRemainingPath();
        			System.out.println("\nPath found :D :D :D");
					if((boolean) PlanningSettings.get("debug")) {
						ProductAutomaton.printPath(path);
						System.out.println("Concrete movement: ");
						Iterator<DefaultEdge> it_move = urrg.movement.iterator();
						DefaultEdge e = it_move.next();
						Point2D e_p = urrg.getGraph().getEdgeSource(e).getPoint();
						System.out.println("(" + e_p.getX() + ", " + e_p.getY() + ") \t" + Environment.getLabelling().getLabel(e_p));
						while (it_move.hasNext()){
							e_p = urrg.getGraph().getEdgeSource(e).getPoint();
							System.out.println("(" + e_p.getX() + ", " + e_p.getY() + ") \t" + Environment.getLabelling().getLabel(e_p));
							e = it_move.next();
						}
						System.out.println("\nMovement: ");
						ProductAutomaton.printPath(urrg.movementBDD);
						System.out.println("\nRemaining path: ");
						ProductAutomaton.printPath(remainingPath);
					}
					if(remainingPath !=null) {
						finalPath = urrg.liftPath(remainingPath);
					}
            		break;
            	}
        	}
        	pathTime += System.nanoTime() - startTime;
        }
        printOutput();
		if((boolean) PlanningSettings.get("debug")) {
			System.out.print("Moving time (in ms):");
			System.out.println(urrg.moveTime / 1000000);
		}

		return new Object[] {
				iterationNumber,
				rrg.totalSampledPoints,
				rrg.totalPoints,
				moveLength,
				pathLength,
				moveLength + pathLength,
				totalTime
		};
    }
	
	/**
	 * Do exploration first, then do the planning
	 * @throws Exception
	 */
	public Object[] firstExplThenPlan() throws Exception {
		KnownGrid grid = new KnownGrid(env, (float) PlanningSettings.get("gridSize"));
		startTime = System.nanoTime();
		System.out.print("Starting exploration ...");
		moveLength = exploreDiscretization(grid);
		System.out.println(" finished");
		explTime = System.nanoTime() - startTime;

		KnownRRG krrg = (KnownRRG) rrg;
		krrg.setGrid(grid);
		krrg.setStartingPoint(krrg.currentRobotPosition);
		ArrayList<BDD> movementBDD = new ArrayList<>();
		movementBDD.add(Environment.getLabelling().getLabel(krrg.currentRobotPosition));

		System.out.print("Starting planning ... ");
		double timeout = (double) PlanningSettings.get("timeout");
		double currentTime = System.nanoTime()/1000000.0F;

		boolean computePath = true;
		while( currentTime - (beginTime/1000000) < timeout ){
			iterationNumber++;

			if((boolean) PlanningSettings.get("debug")) {
				System.out.println("Iter num: " + iterationNumber);
			}
        	
        	if ( (boolean) PlanningSettings.get("useAdvice") ) {
				updateAdvice();
			}

			BDD transitions = sampleBatch(rrg);
			if ( transitions == null ) {
				continue;
			}
        	if( productAutomaton.isAcceptingTransition(transitions) ) {
				computePath = true;
			}
        	learn(transitions);
        	
        	if(((double) PlanningSettings.get("timeout")) == Double.MAX_VALUE ) {
				startTime = System.nanoTime();
				if (computePath) {
					ArrayList<BDD> path = productAutomaton.findAcceptingPath(movementBDD);
					if (path != null) {
						System.out.println("Path found :D");
						if ((boolean) PlanningSettings.get("debug")) {
							ProductAutomaton.printPath(path);
						}
						finalPath = rrg.liftPath(path);
						break;
					}
				}
				pathTime += System.nanoTime() - startTime;
			}
			currentTime = System.nanoTime()/1000000.0F;
		}

		if(((double) PlanningSettings.get("timeout")) < Double.MAX_VALUE ) {
			startTime = System.nanoTime();
			ArrayList<BDD> path = productAutomaton.findAcceptingPath(movementBDD);
			if (path != null) {
				System.out.println("Path found :D");
				if ((boolean) PlanningSettings.get("debug")) {
					ProductAutomaton.printPath(path);
				}
				finalPath = rrg.liftPath(path);
			}
			else {
				System.out.println("No path found :(");
			}
			pathTime += System.nanoTime() - startTime;
		}
		System.out.print("Plotting...");
		Pair<Float, Float> length = krrg.plotGraph(finalPath, grid.getGraph());
		System.out.println("done");
		pathLength = length.getSecond();
		printOutput();

		return new Object[] {
				iterationNumber,
				rrg.totalSampledPoints,
				rrg.totalPoints,
				moveLength,
				pathLength,
				moveLength + pathLength,
				totalTime
		};
	}

	private float exploreDiscretization(KnownGrid grid) throws Exception {
		Point2D currentPosition = Environment.getInit();
		grid.initializeGraph();
		grid.knowDiscretization(env, productAutomaton, currentPosition, (float) PlanningSettings.get("sensingRadius"));
		grid.updateInitPoint(currentPosition);
		float length = 0;
		while(! grid.exploredCompletely()) {
			Pair<Point2D, Integer> newMove = grid.findAMove(currentPosition);
			if(newMove == null) {
				break;
			}
			Point2D newPosition = newMove.getFirst();
			grid.updateMovement(currentPosition, newPosition);
			length += grid.findAPath(currentPosition, newPosition);
			currentPosition = newPosition;
			grid.knowDiscretization(env, productAutomaton, currentPosition, (float) PlanningSettings.get("sensingRadius"));
		}
		rrg.setMovement(grid.getMovement());
		rrg.currentRobotPosition = currentPosition;
		return length;
	}
}









