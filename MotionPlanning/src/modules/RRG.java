package modules;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.alg.util.Pair;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;

import com.infomatiq.jsi.Point;
import com.infomatiq.jsi.Rectangle;
import com.infomatiq.jsi.SpatialIndex;
import com.infomatiq.jsi.rtree.RTree;

import abstraction.ProductAutomaton;
import environment.EdgeSupplier;
import environment.Environment;
import environment.Vertex;
import environment.VertexSupplier;
import net.sf.javabdd.BDD;
import settings.PlanningException;
import settings.PlanningSettings;

public abstract class RRG {

	Environment env;
	ProductAutomaton productAutomaton;

	SpatialIndex tree = new RTree();
	Graph<Vertex, DefaultEdge> graph;
	ArrayList<Point> treePoints = new ArrayList<Point>();; // list of all the points in the Rtree/graphRRG
	ArrayList<Pair<BDD, Vertex>> map = new ArrayList<Pair<BDD, Vertex>>(); // map between abstraction and rtree
	Vertex initVertex;  //initial vertex in the graphRRG

	float rrgRadius;
	float maximumRRGStepSize;
	double gamma; // parameter used in RRG radius
	BDD forwardSampledTransitions; 

	public int totalPoints = 0;
	public int totalSampledPoints = 0;
	public int[] adviceSampled = new int[] {0,0,0,0,0,0,0,0,0,0};
	int currentBatchSize = 0;
	boolean endBatch; // flag for ending the current batch
	BDD symbolicTransitionsInCurrentBatch;
	int currentRank;
	public ArrayList<DefaultEdge> movement = new ArrayList<DefaultEdge>();
	public Point2D currentRobotPosition;

	// used for exporting graphRRG
	boolean flagBin = false;
	boolean flagRoom = false;
	boolean flagFirstMove = false;

	/**
	 * Initialise the RRG object with the constructor
	 * @param env
	 */
	public RRG(Environment env) 
	{
		this.env 				= env;
		this.maximumRRGStepSize = (float) PlanningSettings.get("eta");
		float[] sub 			= new float[] {env.getBoundsX()[1]-env.getBoundsX()[0], env.getBoundsY()[1]-env.getBoundsY()[0]};
		this.gamma 				= 2.0 * Math.pow(1.5,0.5) * Math.pow(sub[0]*sub[1]/Math.PI,0.5);
		this.graph 				= new SimpleGraph<Vertex, DefaultEdge>(new VertexSupplier(), new EdgeSupplier(), true);
		this.forwardSampledTransitions = ProductAutomaton.factory.zero();
		this.tree.init(null);
	}
	
	
	/**
	 * set the product automaton
	 * @param productAutomaton
	 */
	public void setProductAutomaton(ProductAutomaton productAutomaton) {
		this.productAutomaton = productAutomaton;
	}
	
	public void setMovement(ArrayList<DefaultEdge> movement) {
		this.movement = movement;
	}
	
	/**
	 * Set initial point
	 * @param p2D
	 * @throws Exception 
	 */
	public void setStartingPoint(Point2D p2D) throws Exception 
	{
		this.initVertex = new Vertex(p2D);
		graph.addVertex(initVertex);
		Rectangle rect 	= new Rectangle((float) p2D.getX(), (float) p2D.getY(), (float) p2D.getX(), (float) p2D.getY());
		Point p 		= new Point((float) p2D.getX(), (float) p2D.getY());
		treePoints.add(p);
		tree.add(rect, totalPoints);
		this.currentRobotPosition = p2D;
		totalPoints++;
	}
	
	/**
	 * Retrun the RRG graphRRG
	 * @return
	 */
	public Graph<Vertex, DefaultEdge> getGraph(){
		return graph;
	}
	
	/**
	 * updates the RRG radius
	 */
	public void updateRrgRadius() {
		if(totalPoints > 1)
			rrgRadius = (float) Math.min(gamma * Math.pow(Math.log(totalPoints)/(totalPoints), (0.5)), maximumRRGStepSize);
		else
			rrgRadius = maximumRRGStepSize;
	}

	/**
	 * adds symbolic transitions from source to target in the set of transition of current batch
	 * @param source
	 * @param target
	 */
	void addSymbolicTransitions(Point2D source, Point2D target) {
		try {
			BDD transition	= (Environment.getLabelling().getLabel(source));
			transition 		= transition.and(productAutomaton.changePreSystemVarsToPostSystemVars(Environment.getLabelling().getLabel(target)));
			if(! Environment.getLabelling().getLabel(source).equals(Environment.getLabelling().getLabel(target))) {
				forwardSampledTransitions = forwardSampledTransitions.or(transition);
			}
			symbolicTransitionsInCurrentBatch.orWith(transition);
			
			transition		= Environment.getLabelling().getLabel(target);
			transition 		= transition.and(productAutomaton.changePreSystemVarsToPostSystemVars(Environment.getLabelling().getLabel(source)));
			symbolicTransitionsInCurrentBatch.orWith(transition);
		}
		catch (Exception e1) {
			e1.printStackTrace();
		}
	}
	
	public void updateMovement(Point2D newPosition) throws Exception {
		Vertex source = findTheVertex(currentRobotPosition);
		if(source == null) {
			source = new Vertex(currentRobotPosition);
			graph.addVertex(source);
			Point2D center = findCellCenter(currentRobotPosition);
			System.out.println("Found Point "+ center);
			Vertex centerV = findTheVertex(center);
			graph.addEdge(source, centerV);
		}
		Vertex target = findTheVertex(newPosition);
		GraphPath<Vertex, DefaultEdge> path = DijkstraShortestPath.findPathBetween(graph, source, target);
		ArrayList<DefaultEdge> edges;
		edges = (ArrayList<DefaultEdge>) path.getEdgeList();
		movement.addAll(edges);
	}
	
	protected Point2D findCellCenter(Point2D p) {
		float i = (float) Math.floor( p.getX() / (float) PlanningSettings.get("discretizationSize") );
		float j = (float) Math.floor( p.getY() / (float) PlanningSettings.get("discretizationSize") );
		i += 0.5;
		j += 0.5;
		i *= (float) PlanningSettings.get("discretizationSize");
		j *= (float) PlanningSettings.get("discretizationSize");
		return new Point2D.Float(i, j);
	}

	/**
	 * find the rank of a transition according to advice
	 * @param advice
	 * @param xNearest2D
	 * @param xNew2D
	 * @param transition
	 * @return
	 */
	protected int findRank(ArrayList<BDD> advice, Point2D xNearest2D, Point2D xNew2D, BDD transition) {
		try {
			if(Environment.getLabelling().getLabel(xNearest2D).equals(Environment.getLabelling().getLabel(xNew2D))) return -1;
			for(int i=0;i<advice.size();i++) {
				if(! transition.and(advice.get(i)).isZero()) return i;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return -1;
	}
	
	/**
	 * get the probability of accepting a transition of some rank
	 * @param rank
	 * @return
	 */
	protected float getProb(int rank) 
	{

		if(rank == -1) {
			return (float) PlanningSettings.get("biasProb");
		}
		else {
			return 1f;
		}
	}
	
	/**
	 * Checks if the edge is collision-free, have been sampled before and selects the edge according to its prob
	 * @param advice
	 * @param xNearest2D
	 * @param xNew2D
	 * @param transition
	 * @return
	 */
	boolean checkValidity(ArrayList<BDD> advice, Point2D xNearest2D, Point2D xNew2D, BDD transition) {
		if(! env.collisionFreeAll(xNearest2D, xNew2D)) return false; // new edge is obstacle free
		if(! forwardSampledTransitions.and(transition).isZero()) return false; // transition already sampled
		if(advice != null) {
			int rank = findRank(advice, xNearest2D, xNew2D, transition);
			float prob = getProb(rank);
			float rand = (float) Math.random();
			if(rand < prob) return true;
			return false;
		} else {
			return true;
		}
	}
	
	/**
	 * Find a vertex in the graphRRG where labelling is
	 * @param label
	 * @return
	 */
	private Vertex findTheVertex(BDD label) 
	{
		Iterator<Pair<BDD, Vertex>> it = map.iterator();
		while(it.hasNext())
		{
			Pair<BDD, Vertex> n = (Pair<BDD, Vertex>) it.next();
			if(! n.getFirst().and(label).isZero())
			{
				return n.getSecond();
			}
		}
		return null;
	}
	
	/**
	 * Find a vertex in the graphRRG whose point is p
	 * @param p
	 * @return
	 */
	protected Vertex findTheVertex(Point2D p) 
	{
		Set<Vertex> vertexSet 	= graph.vertexSet();
		Iterator<Vertex> it 	= vertexSet.iterator();
		Vertex temp;
		while(it.hasNext()) 
		{
			temp 				= it.next();
			if(temp.getPoint().getX() == p.getX()  &&  temp.getPoint().getY() == p.getY())
			{
				return temp;
			}
		}
		return null;
	}
	
	/**
	 * add a vertex to the map
	 * @param v
	 */
	protected void addToMap(Vertex v) {
		try {
			BDD label = Environment.getLabelling().getLabel(v.getPoint());
			if(findTheVertex(label) == null) 	map.add(new Pair<BDD, Vertex>(label, v));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Find distance between two points
	 * @param p
	 * @param q
	 * @return
	 */
	float distance(Point p, Point q) {
		return (float) Math.sqrt(Math.pow(p.x - q.x, 2) + Math.pow(p.y - q.y, 2));
	}
	
	/**
	 * compute distance between two points
	 * @param p
	 * @param q
	 * @return
	 */
	protected float distance(Point2D p, Point2D q) {
		return (float) Math.sqrt(Math.pow(p.getX() - q.getX(), 2)+Math.pow(p.getY() - q.getY(), 2));
	}
	
	/**
	 * give a point in the direction of 'dest' from source at a distance <= maximumRRGStepSize
	 * @param source
	 * @param dest
	 * @return the point
	 */
	public Point2D steer(Point2D source, Point2D dest) 
	{
		float d	= (float) source.distance(dest);
		if(d <= maximumRRGStepSize) {
			return dest;
		}
		else {
			Point2D temp = new Point2D.Float((float) (source.getX() + ((maximumRRGStepSize - 0.00001) * (dest.getX() - source.getX())/d)), (float) (source.getY() + ((maximumRRGStepSize - 0.00001) * (dest.getY() - source.getY())/d)));
			return temp;
		}
	}
	
	
	/**
	 * add an edge in the graphRRG from source to target
	 * @param start
	 * @param end
	 */
	void addGraphEdge(Point2D start, Point2D end) {
		Vertex source	= findTheVertex(start);
		Vertex target 	= findTheVertex(end);
		if(target == null) {
			target = new Vertex(end);
			graph.addVertex(target);
		}
		try {
			DefaultEdge edge = graph.addEdge(source, target);
			if(edge != null) {
				graph.setEdgeWeight(edge, distance(start, end));
			}
			addToMap(target);
		} catch (Exception IllegalArgumentException) {}		
	}
	
	/**
	 * convert point object to a point2D object
	 * @param p
	 * @return
	 */
	Point2D convertPointToPoint2D(Point p) {
		return new Point2D.Float(p.x, p.y);
	}
	
	/**
	 * convert point2D object to a point object
	 * @param p
	 * @return
	 */
	protected Point convertPoint2DToPoint(Point2D p) {
		return new Point((float) p.getX(), (float)p.getY());
	}

	/**
	 * Lift the path from abstraction to the graphRRG
	 * @param path
	 * @return 
	 */
	public List<DefaultEdge> liftPath(ArrayList<BDD> path) {
		Vertex source 		= initVertex;
		Vertex dest;
		Iterator<BDD> it 	= path.iterator();
		BDD nextState;
		List<DefaultEdge> finalPath;
		
		//initialize finalPath
		nextState 			= it.next(); // first point is already there
		nextState 			= it.next();
		
		dest = findTheVertex(nextState);
		GraphPath<Vertex, DefaultEdge> nextPath = DijkstraShortestPath.findPathBetween(graph, source, dest);
		finalPath 			= nextPath.getEdgeList();
		
		while(it.hasNext())
		{
			source 			= dest;
			nextState 		= it.next();
			dest 			= findTheVertex(nextState);
			try {
				nextPath 		= DijkstraShortestPath.findPathBetween(graph, source, dest);
			} catch(Exception e) {
				e.printStackTrace();
			}
			finalPath.addAll(nextPath.getEdgeList());
		}
		return finalPath;
	}   
	
	/**
	 * Finds the list of atomic propositions true in an abstract state
	 * @param state
	 * @return
	 * @throws PlanningException
	 */
	private ArrayList<String> findAPList(BDD state) throws PlanningException 
	{
		ArrayList<String> list		= new ArrayList<String>();
		for(int i=0; i<ProductAutomaton.numAPSystem; i++) 
		{
			if(! state.and(ProductAutomaton.ithVarSystemPre(i)).isZero()) 
			{
				list.add(ProductAutomaton.apListSystem.get(i));
			}
		}
		list.add(Integer.toString(state.scanVar(ProductAutomaton.propertyDomainPre()).intValue()));
		return list;
	}
	
	/**
	 * prints the list of atomic propsition true at an abstract state
	 * @param state
	 * @throws PlanningException
	 */
	public void printAPList(BDD state) throws PlanningException 
	{
		ArrayList<String> apList	= findAPList(state);
		System.out.print("[");
		for(int j=0; j<apList.size(); j++) 
		{
			if(j < apList.size() - 1) 
			{
				System.out.print(apList.get(j)+",");
			}
			else 
			{
				System.out.print(apList.get(j));
			}
		}
		System.out.print("]  ");
	}
	
//	public Pair<Float, Float> plotGraph(List<DefaultEdge> finalPath)
	
	/**
	 * Does one iteration of the algo with the point xRand
	 * @param advice
	 * @param xRand2D
	 */
	abstract void buildGraph(ArrayList<BDD> advice, Point2D xRand2D);
	
	/**
	 * sample one batch and add them in the graphs accordingly
	 * @param advice
	 * @return
	 * @throws Exception
	 */
	public abstract BDD sampleBatch(ArrayList<BDD> advice) throws Exception;

}
