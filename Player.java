// import the API.
// See xxx for the javadocs.
import bc.*;

import javax.xml.stream.FactoryConfigurationError;
import javax.xml.stream.events.EndElement;
import java.lang.reflect.Array;
import java.util.*;

public class Player {

    public static Random RAND = new Random();
    public static ArrayList<Unit> knownEnemies = new ArrayList<Unit>();

    public static int knownEnemyLocLimit = 30;
    public static float knownEnemyLocRefreshRate = 0.5f;

    public static final int PASSABLE = 1; //these are used for path finding
    public static final int OCCUPIED = 0;

    public static Direction[] DIRECTIONS = Direction.values();

    public static boolean ENGAGE_OPTION = false; //the rangers will try to backaway from enemy if engage option is false
    public static float rangerSafeAdditionalDistance = 15; // when told not to engage, a ranger will keep a distance of (attackRange+rangerSafeAdditionalDistance)

    public static PlanetMap earthMap;
    public static PlanetMap marsMap;
    public static Planet currentPlanet;
    public static int currentPlanetWidth;
    public static int currentPlanetHeight;
    public static PlanetMap currentMap;
    public static int[][] pathBias = {{-1,-1},{0,-1},{1,-1},{-1,0},{1,0},{-1,1},{0,1},{1,1}};

    public static GameController gc;

    public static ArrayList<MapLocation> currentMapAllMapLocs = new ArrayList<MapLocation>(); // used to store all the maplocations for a map, it's used in update real-time map
    public static int[][] visitedMatrix;

    public static float[][] currentResourceMap; //number indicate how much karbonite are there
    public static boolean[][] currentPassableMap; //1 is passable, 0 is not

    public static Team myTeam;
    public static Team enemyTeam;

    //MAIN FUNCTION
    public static void main(String[] args) {
        // Connect to the manager, starting the game
        gc = new GameController();
        myTeam = gc.team();
        enemyTeam = getEnemyTeam(myTeam);

        // Direction is a normal java enum.
        gc.queueResearch(UnitType.Worker);
        gc.queueResearch(UnitType.Ranger);
        gc.queueResearch(UnitType.Healer);
        gc.queueResearch(UnitType.Healer);
        gc.queueResearch(UnitType.Ranger);

        int limit_factory = 3;
        int limit_worker = 12;

        earthMap = gc.startingMap(Planet.Earth);
        marsMap = gc.startingMap(Planet.Mars);
        currentPlanet = gc.planet();

        if (gc.planet()==Planet.Earth){
            currentMap = earthMap;
        }else{
            currentMap = marsMap;
        }

        VecUnit initialUnits = currentMap.getInitial_units();
        for (int i = 0; i < initialUnits.size(); i++) {
            Unit u = initialUnits.get(i);
            System.out.println(u.team()+" "+u.location().mapLocation());
        }

        currentPlanetWidth = (int)currentMap.getWidth();
        currentPlanetHeight = (int)currentMap.getHeight();

        currentResourceMap = new float[currentPlanetWidth][currentPlanetHeight];
        currentPassableMap = new boolean[currentPlanetWidth][currentPlanetHeight];
        System.out.println("map size:"+ currentResourceMap.length+" "+ currentResourceMap[0].length);
        getAllMapLocs();
        initVisitedMatrix();

        while (true) {
            updateResourceMap();
            updatePassableMap();
            updateEnemies();

            long current_round = gc.round();
            if(current_round%10==0){
                ENGAGE_OPTION = true;
            }
            if(current_round%20==0){
                ENGAGE_OPTION = false;
            }

            // for each round
            System.out.println(gc.round()+" t: "+gc.getTimeLeftMs());
            int n_worker = 0;
            int n_factory = 0;
            int n_ranger = 0;
            int n_healer = 0;

            // VecUnit is a class that you can think of as similar to ArrayList<Unit>, but immutable.
            // first get to know how many of each unit we have
            VecUnit units = gc.myUnits();
            for (int i = 0; i < units.size(); i++) {
                Unit unit = units.get(i);
                switch (unit.unitType()){
                    case Factory:
                        n_factory ++;
                        break;
                    case Ranger:
                        n_ranger++;
                        break;
                    case Worker:
                        n_worker++;
                        break;
                    case Healer:
                        n_healer++;
                        break;
                }
            }

            //then do unit logic
            for (int i = 0; i < units.size(); i++) {
                Unit unit = units.get(i);
                int uid = unit.id();
                if (!unit.location().isOnMap()) {
                    continue;
                }
                MapLocation maploc = unit.location().mapLocation();
                Direction d = getRandomDirection();
                VecUnit enemies;
                switch (unit.unitType()) {
                    case Worker:
                        enemies = gc.senseNearbyUnitsByTeam(maploc, unit.visionRange(), enemyTeam);
                        VecUnit closeRangeAllyUnits = gc.senseNearbyUnitsByTeam(maploc, 2, myTeam);
                        boolean hasUnbuildOrUnrepairedFactoryNearby = false;
                        for (int j = 0; j < closeRangeAllyUnits.size(); j++) {
                            Unit other = closeRangeAllyUnits.get(j);
                            if (other.unitType() == UnitType.Factory && other.health() < other.maxHealth()) {
                                hasUnbuildOrUnrepairedFactoryNearby = true;
                            }

                            if (gc.canBuild(uid, other.id())) {
                                gc.build(uid, other.id());
                                break;
                            }

                            if (gc.canRepair(uid, other.id())) {
                                gc.repair(uid, other.id());
                                break;
                            }
                        }

                        if (n_factory < limit_factory) {
                            boolean hasBluePrinted = blueprintFactoryNearby(gc, uid);
                            if (hasBluePrinted) {
                                n_factory++;
                            }
                        }

                        if (n_worker < limit_worker) {
                            boolean hasReplicated = replicateNearby(gc, uid);
                            if (hasReplicated) {
                                n_worker++;
                            }
                        }

                        if (!hasUnbuildOrUnrepairedFactoryNearby) { //move only when there is no building to build nearby
                            if (gc.isMoveReady(uid)) {
                                //first check if there's a building that needs to build nearby
                                VecUnit nearbyAllyUnits = gc.senseNearbyUnitsByTeam(maploc, 8, myTeam);
                                for (int j = 0; j < nearbyAllyUnits.size(); j++) {
                                    Unit other = nearbyAllyUnits.get(j);
                                    if (other.unitType() == UnitType.Factory && other.health() < other.maxHealth()) {
                                        Direction moveDirection = getDirToTargetMapLocGreedy(gc, unit, other.location().mapLocation());
                                        if (gc.canMove(uid, moveDirection)) {
                                            gc.moveRobot(uid, moveDirection);
                                            break;
                                        }
                                    }
                                }
                            }
                        }

                        boolean hasHarvested = harvestNearby(gc, uid);

                        if (!hasHarvested && gc.isMoveReady(uid)) {
                            MapLocation nearestResourceLoc = getANearbyResourceLocation(gc, unit);
                            if (nearestResourceLoc != null) {//if there is a nearby resource location, then try go to that location.
                                d = getDirToTargetMapLocGreedy(gc, unit, nearestResourceLoc);
                            } else {//if not then simply move randomly
                                d = getRandomDirection();
                            }
                            if (gc.canMove(uid, d)) {
                                gc.moveRobot(unit.id(), d);
                            }
                        }

                        break;
                    case Factory:
                        for (int j = 0; j < DIRECTIONS.length; j++) {
                            d = DIRECTIONS[j];
                            if (gc.canUnload(uid, d)) {
                                gc.unload(uid, d);
                                break;
                            }
                        }

                        if (gc.canProduceRobot(uid, UnitType.Ranger ) && Math.random()<0.8) {
                            gc.produceRobot(uid, UnitType.Ranger);
                            n_ranger ++;
                        }else if(gc.canProduceRobot(uid, UnitType.Healer)&& n_ranger>=10 && (n_healer==0 ||(int)(n_ranger/n_healer)>2.2) && Math.random()<0.6) {
                            gc.produceRobot(uid, UnitType.Healer);
                            n_healer++;
                        }

                        break;
                    case Ranger:
                        enemies = gc.senseNearbyUnitsByTeam(maploc, unit.visionRange(), enemyTeam);
                        if (gc.isAttackReady(uid)) {//if seen enemy and can attack, then attack.
                            Unit nearestEnemyToAttack = findNearestUnit_inRangerAttackRange(gc, unit, enemies);
                            if (nearestEnemyToAttack != null && gc.canAttack(uid, nearestEnemyToAttack.id())) {
                                gc.attack(uid, nearestEnemyToAttack.id());
                            }
                        }

                        Direction moveDir;
                        if (enemies.size() > 0 && gc.isMoveReady(uid)) {//if there are enemies in range
                            if (!gc.isAttackReady(uid)) {
                                //if already attacked, then try move away from enemy
                                Unit nearestEnemy = findNearestUnit(maploc, enemies);
                                moveDir = getDirAwayTargetMapLocGreedy(gc, unit, nearestEnemy.location().mapLocation());
                                if (gc.canMove(uid, moveDir)) {
                                    gc.moveRobot(uid, moveDir);
                                }
                            } else {//if haven't attacked, then either enemy is too far away or too close
                                Unit nearestEnemy = findNearestUnit(maploc, enemies);
                                float distance = unit.location().mapLocation().distanceSquaredTo(nearestEnemy.location().mapLocation());

                                if (ENGAGE_OPTION) {//try keep distance at attack range if engage is allowed
                                    if (distance < unit.rangerCannotAttackRange()) {//if too close
                                        moveDir = getDirAwayTargetMapLocGreedy(gc, unit, nearestEnemy.location().mapLocation());
                                    } else {//if too far
                                        moveDir = getDirToTargetMapLocGreedy(gc, unit, nearestEnemy.location().mapLocation());
                                    }
                                } else {//play defensive if engage is not allowed, backup when closer than safe distance,
                                    float safeDistance = unit.attackRange() + rangerSafeAdditionalDistance;
                                    if (distance < safeDistance) {
                                        moveDir = getDirAwayTargetMapLocGreedy(gc, unit, nearestEnemy.location().mapLocation());
                                    } else {
                                        moveDir = getDirToTargetMapLocGreedy(gc, unit, nearestEnemy.location().mapLocation());
                                    }
                                }

                                if (gc.canMove(uid, moveDir)) {
                                    gc.moveRobot(uid, moveDir);
                                }
                                //now we moved, we check again if the unit can attack anything.
                                //NOTICE THAT NOW THE UNIT'S LOCATION IS CHANGED, so it might be able to attack
                                //an enemy that cannot be attacked before
                                Unit nearestEnemyToAttack = findNearestUnit_inRangerAttackRange(gc, unit, enemies);
                                if (nearestEnemyToAttack != null && gc.canAttack(uid, nearestEnemyToAttack.id())) { //if can attack, attack
                                    gc.attack(uid, nearestEnemyToAttack.id());
                                }
                            }
                        } else if (gc.isMoveReady(uid)) {//if no enemies in range and can move
                            if (knownEnemies.size() > 0) {//if we know where the enemy might be
                                MapLocation nearestEnemyLocation = findNearestEnemyFromKnownEnemies(maploc);
                                Direction seekDir = getDirToTargetMapLocGreedy(gc, unit, nearestEnemyLocation);
                                if (gc.canMove(uid, seekDir)) {
                                    gc.moveRobot(uid, seekDir);
                                }
                            } else {//move randomly
                                d = getRandomDirection();
                                if (gc.canMove(uid, d)) {
                                    gc.moveRobot(uid, d);
                                }
                            }
                        }
                        break;
                    case Healer:
                        enemies = gc.senseNearbyUnitsByTeam(maploc, unit.visionRange(), enemyTeam);

                        if (gc.isHealReady(uid)) {
                            VecUnit friends = gc.senseNearbyUnitsByTeam(maploc, unit.attackRange(), myTeam);
                            Unit friendToHeal = findFriendToHeal(gc, unit, friends);
                            if (gc.canHeal(uid, friendToHeal.id())) {
                                gc.heal(uid, friendToHeal.id());
                            }
                        }

                        if (gc.isMoveReady(uid)) {
                            if (enemies.size() > 0) {//if seen enemies
                                Unit nearestEnemy = findNearestUnit(maploc, enemies);
                                moveDir = getDirAwayTargetMapLocGreedy(gc,unit,nearestEnemy.location().mapLocation());
                                if(gc.canMove(uid,moveDir)){
                                    gc.moveRobot(uid,moveDir);
                                }
                            } else {//if no enemy in sight
                                if (knownEnemies.size() > 0) {//if we know where the enemy might be
                                    MapLocation nearestEnemyLocation = findNearestEnemyFromKnownEnemies(maploc);

                                    float distance = maploc.distanceSquaredTo(nearestEnemyLocation);
                                    if(distance>unit.visionRange()+12){
                                        Direction seekDir = getDirToTargetMapLocGreedy(gc, unit, nearestEnemyLocation);
                                        if (gc.canMove(uid, seekDir)) {
                                            gc.moveRobot(uid, seekDir);
                                        }
                                    }else{
                                        d = getRandomDirection();
                                        if (gc.canMove(uid, d)) {
                                            gc.moveRobot(uid, d);
                                        }
                                    }
                                } else {//move randomly
                                    d = getRandomDirection();
                                    if (gc.canMove(uid, d)) {
                                        gc.moveRobot(uid, d);
                                    }
                                }
                            }
                        }
                        break;
                }
            }
            // Submit the actions we've done, and wait for our next turn.
            gc.nextTurn();
        }
    }//END OF MAIN FUNCTION

    public static void getAllMapLocs(){
        //call this at the beginning of the game, to get all map locations for later usage.
        for (int i = 0; i < currentPlanetWidth; i++) {
            for (int j = 0; j < currentPlanetHeight; j++) {
                MapLocation mapLoc = new MapLocation(currentPlanet,i,j);
                currentMapAllMapLocs.add(mapLoc);
            }
        }
    }

    public static Direction BFSToMapLocation(MapLocation ourLoc,MapLocation targetLoc){
        //given our unit's location, try to find a route with A* to the target location
        //need to clear visitedmatrix here every time you call this #TODO

        int[] startloc = {ourLoc.getX(),ourLoc.getY()};
        int[] targetloc = {targetLoc.getX(),targetLoc.getY()};
        ArrayDeque<int[]> frontier = new ArrayDeque<int[]>();
        frontier.add(startloc);

        boolean foundPath = false;
        while(!frontier.isEmpty()){

            int[] loc = frontier.poll();
            if (locCompare(loc,targetloc)){
                //means found target
                foundPath = true;
                break;
            }

            ArrayList<int[]> neighbors = getNeighbors(loc);
            visitedMatrix[loc[0]][loc[1]] = 0;//0 means unvisited, 1 means visited
            frontier.addAll(neighbors);
        }

        if(foundPath){
            //return the next direction
            return Direction.Center; //TODO
        }else {
            return Direction.Center;
        }
    }

    public static boolean locCompare(int[] loc1,int[] loc2){
        //used in path finding
        return (loc1[0]==loc2[0] && loc1[1] == loc2[1]);
    }

    public static ArrayList<int[]> getNeighbors(int[] loc){
        int x = loc[0];
        int y = loc[1];
        ArrayList<int[]> neighbors = new ArrayList<int[]>();
        for (int i = 0; i < 8; i++) {
            int dx = pathBias[i][0];
            int dy = pathBias[i][1];
            int newx = x+dx;
            int newy = y+dx;
            if(newx>=0 && newy>=0 && newx<currentPlanetWidth && newy <currentPlanetHeight && visitedMatrix[newx][newy]==1){
                int[] pair = {newx,newy};
                neighbors.add(pair);
            }
        }
        return neighbors;
    }

    public static void initVisitedMatrix(){
        //call this every round to update passable terrains (if a maploc has a unit then it's seen as not passable.)
        visitedMatrix = new int[currentPlanetWidth][currentPlanetHeight];
    }


    public static void updatePassableMap(){
        //call this every round to update passable terrains (if a maploc has a unit then it's seen as not passable.)
        for (int i = 0; i < currentMapAllMapLocs.size(); i++) {
            MapLocation mapLoc = currentMapAllMapLocs.get(i);
            int x = mapLoc.getX();
            int y = mapLoc.getY();
            if( currentMap.isPassableTerrainAt(mapLoc)==0 || (gc.canSenseLocation(mapLoc) && gc.hasUnitAtLocation(mapLoc))){
                //if there is a obstacle or a unit there, then this location is not passable.
                currentPassableMap[x][y] = false;
            }else{
                currentPassableMap[x][y] = true;
            }
        }
    }

    public static void updateEnemies() {
        //call this each round to update known enemies (their locations and everything else)
        knownEnemies.clear();//first clear enemies from last round
        for (int i = 0; i < currentMapAllMapLocs.size(); i++) {
            MapLocation mapLoc = currentMapAllMapLocs.get(i);
            int x = mapLoc.getX();
            int y = mapLoc.getY();
            if(gc.canSenseLocation(mapLoc) && gc.hasUnitAtLocation(mapLoc)) {
                //if there is a unit at this location
                Unit unit = gc.senseUnitAtLocation(mapLoc);
                if (unit.team()==enemyTeam){
                    knownEnemies.add(unit);
                }
            }
        }
    }

    public static void printPassableMapDEBUG(){
        //debug use only
        for (int y = currentPlanetHeight-1; y >= 0; y--) {
            String s = "";
            for (int x = 0; x < currentPlanetWidth; x++) {
                s+=(currentPassableMap[x][y]+" ");
            }
            System.out.println(s);
        }
    }

    public static void updateResourceMap(){
        for (int i = 0; i < currentMapAllMapLocs.size(); i++) {
            MapLocation mapLoc = currentMapAllMapLocs.get(i);
            if (gc.canSenseLocation(mapLoc)){
                //if this location is in sight, then update its resource location
                int x = mapLoc.getX();
                int y = mapLoc.getY();
                currentResourceMap[x][y] = gc.karboniteAt(mapLoc);
            }
        }
    }


    public static Direction getRandomDirection(){
        Direction[] directions = Direction.values();
        int i = RAND.nextInt(directions.length);
        return directions[i];
    }


    public static Direction getDirToTargetMapLocGreedy(GameController gc, Unit ourUnit, MapLocation targetLoc) {
        // the unit will try to move greedily to a direction approximately towards the target location.
        Direction dirToTarget = ourUnit.location().mapLocation().directionTo(targetLoc);
        if (dirToTarget == Direction.Center) {
            return dirToTarget;
        }
        int directdirectionIndex = dirToTarget.swigValue();
        for (int i = 0; i < 4; i++) {
            int newDirIndex = (directdirectionIndex + (i+8)) % 8;
            Direction newDirection = Direction.swigToEnum(newDirIndex);
            if (gc.canMove(ourUnit.id(), newDirection)) {
                return newDirection;
            }

            newDirIndex = (directdirectionIndex - (i-8)) % 8;
            newDirection = Direction.swigToEnum(newDirIndex);
            if (gc.canMove(ourUnit.id(), newDirection)) {
                return newDirection;
            }
        }
        return Direction.Center;
    }

    public static Direction getDirAwayTargetMapLocGreedy(GameController gc, Unit ourUnit, MapLocation targetLoc) {
        // the unit will try to move greedily to a direction approximately towards the target location.
        Direction directdirectionIndex = targetLoc.directionTo(ourUnit.location().mapLocation());
        if (directdirectionIndex == Direction.Center) {
            return directdirectionIndex;
        }
        int dirIndex = directdirectionIndex.swigValue();
        for (int i = 0; i < 4; i++) {
            int newDirIndex = (dirIndex + (i+8)) % 8;
            Direction newDirection = Direction.swigToEnum(newDirIndex);
            if (gc.canMove(ourUnit.id(), newDirection)) {
                return newDirection;
            }

            newDirIndex = (dirIndex - (i-8)) % 8;
            newDirection = Direction.swigToEnum(newDirIndex);
            if (gc.canMove(ourUnit.id(), newDirection)) {
                return newDirection;
            }
        }
        return Direction.Center;
    }

    public static MapLocation findNearestEnemyFromKnownEnemies(MapLocation ourLoc){
        // given our maplocation and a list of locations, find the nearest location
        float minDistance = 99999999;
        MapLocation nearestLoc = ourLoc;
        for (int i = 0; i < knownEnemies.size(); i++) {
            MapLocation otherLoc = knownEnemies.get(i).location().mapLocation();
            float distance = ourLoc.distanceSquaredTo(otherLoc);
            if (distance < minDistance) {
                minDistance = distance;
                nearestLoc = otherLoc;
            }
        }
        return nearestLoc;

    }


    public static ArrayList<Integer> getShuffledIndexes(long size){
        ArrayList<Integer> toreturn = new ArrayList<Integer>();
        for (int i = 0; i < size; i++) {
            toreturn.add(i);
        }
        Collections.shuffle(toreturn);
        return toreturn;
    }

    public static MapLocation getANearbyResourceLocation(GameController gc, Unit worker){
        //given a worker's current location, check
        long checkRadius = worker.visionRange();
        VecMapLocation nearbyLocations = gc.allLocationsWithin(worker.location().mapLocation(),checkRadius); //this is all the locations a worker can see
        ArrayList<Integer> shuffledIndexes = getShuffledIndexes(nearbyLocations.size());

        for (int i = 0; i < nearbyLocations.size(); i++) {
            int index = shuffledIndexes.get(i);
            MapLocation checkLoc = nearbyLocations.get(index);
            if (gc.karboniteAt(checkLoc)>0){
                return checkLoc;
            }
        }
        return null;
    }

    public static Unit findNearestUnit(MapLocation ourLoc, VecUnit units){
        float minDistance = 99999999;
        Unit nearestUnit = units.get(0);
        for (int i = 0; i < units.size(); i++) {
            Unit other = units.get(i);
            float distance = ourLoc.distanceSquaredTo(other.location().mapLocation());
            if (distance<minDistance){
                minDistance = distance;
                nearestUnit = other;
            }
        }
        return nearestUnit;
    }


    public static Team getEnemyTeam(Team myteam){
        Team enemyTeam;
        if (myteam==Team.Blue){
            enemyTeam = Team.Red;
        }else{
            enemyTeam = Team.Blue;
        }
        return enemyTeam;
    }

    public static Unit findNearestUnit_inRangerAttackRange(GameController gc,Unit ranger, VecUnit units){
        MapLocation maploc = ranger.location().mapLocation();
        // this method finds nearest unit that ranger can attack
        // so it will not return a unit that is too close to the ranger
        float minDistance = 99999999;
        Unit nearestUnit = null;
        for (int i = 0; i < units.size(); i++) {
            Unit other = units.get(i);
            float distance = maploc.distanceSquaredTo(other.location().mapLocation());
            if (distance<=minDistance && gc.canAttack(ranger.id(),other.id())){
                minDistance = distance;
                nearestUnit = other;
            }
        }
        return nearestUnit;
    }

    public static Unit findFriendToHeal(GameController gc, Unit healer, VecUnit friendsInHealRange){
        MapLocation maploc = healer.location().mapLocation();
        Unit unitToHeal = null;
        float maxHpLoss = -99999;

        for (int i = 0; i < friendsInHealRange.size(); i++) {
            Unit other = friendsInHealRange.get(i);
            float hpLoss = other.maxHealth() - other.health();
            if(hpLoss>maxHpLoss){
                maxHpLoss = hpLoss;
                unitToHeal = other;
            }
        }
        return unitToHeal;
    }

    public static boolean blueprintFactoryNearby(GameController gc, int uid){
        Direction d = Direction.Center;
        for (int j = 0; j < DIRECTIONS.length; j++) {
            d = DIRECTIONS[j];
            if (gc.canBlueprint(uid,UnitType.Factory,d)){
                gc.blueprint(uid,UnitType.Factory,d);
                return true;
            }
        }
        return false;
    }

    public static boolean harvestNearby(GameController gc,int uid){
        Direction d = Direction.Center;
        for (int j = 0; j < DIRECTIONS.length; j++) {
            d = DIRECTIONS[j];
            if (gc.canHarvest(uid,d)){
                gc.harvest(uid,d);
                return true;
            }
        }
        return false;
    }

    public static boolean replicateNearby(GameController gc,int uid){
        Direction d = Direction.Center;
        for (int j = 0; j < DIRECTIONS.length; j++) {
            d = DIRECTIONS[j];
            if (gc.canReplicate(uid,d)){
                gc.replicate(uid,d);
                return true;//means successfully replicated
            }
        }
        return false; //means didn't replicate
    }

    public static void initResourceMap(float[][] resourceMap){
        for (int i = 0; i < resourceMap.length; i++) {

        }
    }

}