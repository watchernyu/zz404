// import the API.
// See xxx for the javadocs.
import bc.*;
import java.util.Collections;

import java.util.Random;
import java.util.ArrayList;
public class Player {

    public static Random RAND = new Random();
    public static ArrayList<MapLocation> knownEnemyLocations=new ArrayList<MapLocation>();
    public static int knownEnemyLocLimit = 30;
    public static float knownEnemyLocRefreshRate = 0.5f;

    public static int EARTH_START_STATE = 0;
    public static int EATH_MIDDLE_STATE = 1;

    public static Direction[] DIRECTIONS = Direction.values();

    public static boolean ENGAGE_OPTION = false; //the rangers will try to backaway from enemy if engage option is false
    public static float rangerSafeAdditionalDistance = 15; // when told not to engage, a ranger will keep a distance of (attackRange+rangerSafeAdditionalDistance)

    //MAIN FUNCTION
    public static void main(String[] args) {
        // Connect to the manager, starting the game
        GameController gc = new GameController();
        Team myteam = gc.team();
        Team enemyTeam = getEnemyTeam(myteam);

        // Direction is a normal java enum.

        gc.queueResearch(UnitType.Worker);
        gc.queueResearch(UnitType.Ranger);
        gc.queueResearch(UnitType.Healer);
        gc.queueResearch(UnitType.Healer);
        gc.queueResearch(UnitType.Ranger);

        int limit_factory = 3;
        int limit_worker = 12;
        int earth_current_state = EARTH_START_STATE; //in the beginning, we use this state

        while (true) {
            long current_round = gc.round();
            if(current_round%10==0){
                ENGAGE_OPTION = true;
            }
            if(current_round%20==0){
                ENGAGE_OPTION = false;
            }

            // for each round
            System.out.println("Current round: "+gc.round());
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
                        if (enemies.size() > 0) {
                            updateKnownEnemyLocations(enemies.get(0).location().mapLocation());
                        }

                        VecUnit closeRangeAllyUnits = gc.senseNearbyUnitsByTeam(maploc, 2, myteam);
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
                                VecUnit nearbyAllyUnits = gc.senseNearbyUnitsByTeam(maploc, 8, myteam);
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
                        if (enemies.size() > 0) {
                            updateKnownEnemyLocations(enemies.get(0).location().mapLocation());
                        }

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
                            if (knownEnemyLocations.size() > 0) {//if we know where the enemy might be
                                MapLocation nearestEnemyLocation = findNearestLocation(maploc, knownEnemyLocations);
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
                        if (enemies.size() > 0) {
                            updateKnownEnemyLocations(enemies.get(0).location().mapLocation());
                        }

                        if (gc.isHealReady(uid)) {
                            VecUnit friends = gc.senseNearbyUnitsByTeam(maploc, unit.attackRange(), myteam);
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
                                if (knownEnemyLocations.size() > 0) {//if we know where the enemy might be
                                    MapLocation nearestEnemyLocation = findNearestLocation(maploc, knownEnemyLocations);

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

    public static Direction getRandomDirection(){
        Direction[] directions = Direction.values();
        int i = RAND.nextInt(directions.length);
        return directions[i];
    }

    public static void updateKnownEnemyLocations(MapLocation enemyMapLoc) {
        // when a unit encountered an enemy, simply call this function!
        if (knownEnemyLocations.size() < knownEnemyLocLimit) {
            knownEnemyLocations.add(enemyMapLoc);
            return;
        }
        if (Math.random() < knownEnemyLocRefreshRate) {
            int indexToUse = RAND.nextInt(knownEnemyLocLimit);
            knownEnemyLocations.set(indexToUse, enemyMapLoc);//replace old info with new
        }
        return;
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

    public static MapLocation findNearestLocation(MapLocation ourLoc, ArrayList<MapLocation> otherLocs) {
        // given our maplocation and a list of locations, find the nearest location
        float minDistance = 99999999;
        MapLocation nearestLoc = ourLoc;
        for (int i = 0; i < otherLocs.size(); i++) {
            MapLocation otherLoc = otherLocs.get(i);
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

}