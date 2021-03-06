/* Copyright (c) 2017-2020 MIT 6.031 course staff, all rights reserved.
 * Redistribution of original or derived work requires permission of course staff.
 */
package setgame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ADT representing a Set game board.
 * Mutable and threadsafe.
 */
public class Board {
    
    /**
     * Returns a board with a shuffled list of all cards in the deck..
     * 
     * @param attributes the number of attributes a card should have (currently supports 3, 4)
     * @return a Board with a shuffled list of all possible combinations of attributes, each combination representing a card
     */
    public static Board generateRandom(int attributes) {
        List<Card> cards = generateRandomCards(attributes);
        return new Board(cards, attributes);
    }
    
    /**
     * Returns a shuffled list of all cards in the deck.
     * @param attributes the number of attributes a card should have
     * @return a shuffled list of all possible combinations of attirbutes
     */
    public static List<Card> generateRandomCards(int attributes) {
        List<Card> cards = new ArrayList<>();
        
        // attempt at avoiding magic numbers
//        int numCards = 3;
//        int[] indices = {numCards, numCards, numCards, numCards};
//        
//        for(int i=attributes; i<indices.length; i++) {
//            indices[i] = 1;
//        }
        
        for (Card.Color color: Card.Color.values()) {
            if (attributes == 1) {
                cards.add(new Card(color, Card.Number.ONE, Card.Shading.SOLID, Card.Shape.SQUIGGLE));
            } else {
                for (Card.Number number: Card.Number.values()) {
                    if (attributes == 2) {
                        cards.add(new Card(color, number, Card.Shading.SOLID, Card.Shape.SQUIGGLE));
                    } else {
                        for (Card.Shading shading: Card.Shading.values()) {
                            if (attributes == 3) {
                                cards.add(new Card(color, number, shading, Card.Shape.SQUIGGLE));
                            } else {
                                for (Card.Shape shape: Card.Shape.values()) {
                                    cards.add(new Card(color, number, shading, shape));
                                }
                            }
                        }
                    }
                }
            }
        }
        
        Collections.shuffle(cards);
        return cards;
    }
    
    
    /** A listener for the Board. */
    public interface BoardListener {
        /** Called when the Board changes: when someone declares a set, and when cards are removed or added.
          */
        public void boardChanged(); 
    }
    
    private static final int DEFAULT_ROWS = 3;
    private static final int SET_SIZE = 3;
    private static final long TIME_LIMIT_IN_MILLIS = 5000L;
    
    private List<List<Card>> gameBoard;
    private Map<String, Integer> scores;
    private Queue<Card> cardsRemaining;
    
    private String activePlayer;
    private List<Square> squaresHeld;
    private Set<String> votes;
    private Queue<String> declareQueue;
    private long timeOut;
    private final int defaultColumns;
    
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> result;
    private Map<String, ScheduledFuture<?>> playerTimeouts = new HashMap<>();
    
    private Set<BoardListener> listeners = new HashSet<>();
    
    // TODO complete this section if we want to be thorough
    /* Abstraction function:
     *    AF(gameBoard, scores, heldSquares, isFirst, squareStates, squareQueues, listeners) = 
     *      a game of Memory Scramble with dimensions gameBoard.size() by gameBoard.get(0).size(),
     *      with the text of a card at position (row, col) on the board 
     *      found in gameBoard.get(row-1).get(col-1), the state of that card being held in squareStates.get(new Square(row, col)),
     *      a blocking queue of size 1 holding the current player holding that card being found in squareQueues.get(new Square(row, col)),
     *      and for every player with playerID playing in the game, their score is found in scores.get(playerID),
     *      the squares they currently control is in heldSauares.get(playerID), and whether their next flip will be a "first card"
     *      (as opposed to a "second card") is held in isFirst.get(playerID);
     *      all BoardListeners on the Board are held in listeners
     *    
     * Representation invariant:
     *    all rows in gameBoard are of the same length, and there is at least 1 row and 1 column
     *    every List stored as a value in heldSquares has at most 2 elements
     *    every Square with row number between 1 and the number of rows on the Board, and with column number
     *      between 1 and the number of columns on the Board, must be present as a key in squareStates and squareQeueus
     * 
     * Safety from rep exposure:
     *    all fields are private
     *    all getter methods return immutable values (String, State, int) or explicitly make a defensive copy of 
     *      a mutable value before returning it
     *    constructors make defensive copies of the inputs given; parseFromFile and generateRandom both use the first constructor
     *      taking in a list of rows of card texts, which explicitly makes a copy of each row before inserting it into the Board
     * 
     * Thread safety argument:
     *      all fields are threadsafe datatypes and contain threadsafe datatypes
     *      almost all methods besides constructors are getter methods, which are safe due to the above
     *      for those methods which mutate the rep:
     *          addPlayer: lock allows only one new player to be added at a time, and it only adds new keys to the 
     *              Maps in the rep, which does not interfere with other players
     *          addBoardListener, removeBoardListener: require lock on the listeners Set to add/remove listeners
     *          checkMatch: called only within flipCard when held by a lock for a square controlled 
     *              by a player; the only possible concurrency problems arise when taking from the BlockingQueues 
     *              for the squares they control, but they are guaranteed to be the current and only entry in the BlockingQueue
     *          flipCard: every access to a particular square's current state and BlockingQueue is protected 
     *              requiring the lock for that square's BlockingQueue; the only exception is the line implementing 
     *              rule 1D, which waits when flipping a "first card" until a square's BlockingQueue is empty before 
     *              adding to it, but this line uses a blocking and threadsafe BlockingQueue and cannot interfere with 
     *              the performance of other threads
     */

    /**
     * Constructs an instance of Board, a game of Set with 3 rows and 4 columns.
     * @param cards a list of cards for the Board, 
     * @param attributes the number of attributes being used
     */
    public Board(List<Card> cards, int attributes) {
        defaultColumns = attributes;
        resetGame(cards);
        checkRep();
    }
    
    /**
     * Performs a reset of the game.
     * @param cards the list of cards used for the game
     */
    public synchronized void resetGame(List<Card> cards) {
        List<Card> cardsCopy = new ArrayList<>(cards);
        int counter = 0;
        gameBoard = new ArrayList<>();
        
//        defaultColumns = attributes;
        
        for (int i=0; i<DEFAULT_ROWS; i++) {
            List<Card> newRow = new ArrayList<>();
            for (int j=0; j<defaultColumns; j++) {
                newRow.add(cardsCopy.get(counter));
                counter += 1;
            }
            gameBoard.add(Collections.synchronizedList(new ArrayList<>(newRow)));
        }
        
        gameBoard = Collections.synchronizedList(gameBoard);
        scores = new ConcurrentHashMap<>();
        
        // linked list is more efficient for removing the first card
        cardsRemaining = new LinkedList<>(cardsCopy.subList(DEFAULT_ROWS*defaultColumns, cardsCopy.size()));
        activePlayer = "";
        squaresHeld = Collections.synchronizedList(new ArrayList<>());
        votes = Collections.synchronizedSet(new HashSet<>());
        declareQueue = new LinkedList<>();
        executor = Executors.newSingleThreadScheduledExecutor();
    }
    
    /**
     * Assert the representation invariant is true.
     */
    private void checkRep() {
        assert gameBoard.size() > 0;
        assert gameBoard.get(0).size() > 0;
        int rowLength = gameBoard.get(0).size();
        for (int i=0; i<gameBoard.size(); i++) {
            assert gameBoard.get(i).size() == rowLength;
        }
    }

    @Override
    public String toString() {
        return gameBoard.toString();
    }
    
    @Override
    public boolean equals(Object that) {
        return that instanceof Board && this.sameValue((Board) that);
    }
    
    /**
     * Returns true if the two Boards have the same values in all fields except queues and listeners.
     * @param that another Board
     * @return whether the two have the same value
     */
    private boolean sameValue(Board that) {
        return this.gameBoard.equals(that.gameBoard) // TODO update when finalized instance variables
                && this.scores.equals(that.scores)
                && this.cardsRemaining.equals(that.cardsRemaining)
                && this.activePlayer.equals(that.activePlayer)
                && this.votes.equals(that.votes)
                && this.declareQueue.equals(that.declareQueue)
                && this.timeOut == that.timeOut
                && this.defaultColumns == that.defaultColumns
                && this.executor.equals(that.executor)
                && this.result.equals(that.result)
                && this.listeners.equals(that.listeners);
    }
    
    @Override
    public int hashCode() { 
        int sum = 0;
        for (int i=0; i<gameBoard.size(); i++) {
            List<Card> row = new ArrayList<>(gameBoard.get(i));
            for (int j=0; j<row.size(); j++) {
                sum += row.get(j).hashCode();
            }
        }
        checkRep();
        return sum;
    }
    
    /**
     * Adds a new player to the current game board.
     * @param playerID a unique ID for a particular player
     * @return true if the player was added successfully, false if the playerID has been already taken
     */
    public synchronized boolean addPlayer(String playerID) {
        synchronized (scores) {
            if (scores.containsKey(playerID)) {
                return false;
            }
            scores.put(playerID, 0);
            callListeners();
            checkRep();
            return true;
        }
    }
    
    /**
     * Finds if a player is playing the game.
     * @param playerID a unique ID for a player
     * @return whether the player is playing
     */
    public synchronized boolean isPlayer(String playerID) {
        return scores.containsKey(playerID);
    }
    
    /**
     * Gets the number of rows in the Board.
     * @return the number of rows
     */
    public synchronized int getNumRows() {
        return gameBoard.size();
    }
    
    /**
     * Gets the number of columns in the Board.
     * @return the number of columns
     */
    public synchronized int getNumCols() {
        return gameBoard.get(0).size();
    }
    
    /**
     * Retrieves a game board row.
     * @param row the row, must lie between 0 (inclusive) and the number of rows in the game board (exclusive)
     * @return the specified row
     */
    public synchronized List<Card> getRow(int row) {
        return new ArrayList<>(gameBoard.get(row));
    }
    
    /**
     * Retrieves a game board column.
     * @param col the column, must lie between 0 (inclusive) and the number of columns in the game board (exclusive)
     * @return the specified column
     */
    public synchronized List<Card> getColumn(int col) {
        List<Card> column = new ArrayList<>();
        for (int i=0; i<gameBoard.size(); i++) {
            column.add(gameBoard.get(i).get(col));
        }
        checkRep();
        return column;
    }
    
    /**
     * Retrieves a given card.
     * @param square the coordinates of the card requested
     * @return the text of the requested card
     */
    public synchronized Card getCard(Square square) {
        return gameBoard.get(square.getRow()).get(square.getCol());
    }
    
    /**
     * Sets a given square to the given Set card.
     * @param square
     * @param card
     */
    public synchronized void setCard(Square square, Card card) {
        gameBoard.get(square.getRow()).set(square.getCol(), card);
    }
    
    /**
     * Returns the squares currently being held by a player.
     * @return a copy of the list of squares held
     */
    public synchronized List<Square> getSquaresHeld() {
        return new ArrayList<>(squaresHeld);
    }
    
    /**
     * Adds a listener to the Board.
     * @param listener called when the Board changes
     */
    public synchronized void addBoardListener(BoardListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }
    
    /**
     * Removes a listener from the Board.
     * @param listener which will no longer be called when the Board changes
     */
    public synchronized void removeBoardListener(BoardListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }
    
    private synchronized void callListeners() {
        for (BoardListener listener: Set.copyOf(listeners)) {
            listener.boardChanged();
        }
    }
    
    /**
     * Returns the scores of the Board at present.
     * @return a Map mapping player IDs to their scores
     */
    public synchronized Map<String, Integer> getScores() {
        return new HashMap<>(scores);
    }
    
    /**
     * Determines whether the three cards held make a Set, as defined by the game rules.
     * @return whether the three given cards is a set
     */
    public synchronized boolean checkSet() {
        Set<Card.Color> colors = new HashSet<>();
        Set<Card.Number> numbers = new HashSet<>();
        Set<Card.Shading> shadings = new HashSet<>();
        Set<Card.Shape> shapes = new HashSet<>();
        for (int i=0; i<squaresHeld.size(); i++) {
            Card card = getCard(squaresHeld.get(i));
            colors.add(card.color());
            numbers.add(card.number());
            shadings.add(card.shading());
            shapes.add(card.shape());
        }
        return (colors.size()*numbers.size()*shadings.size()*shapes.size())%2 != 0; // the sets should all be either of size 1 or size 3
    }
    
    /**
     * Schedule a time limit for a player to declare a set.
     */
    public synchronized void scheduleTimeout() {
        result = executor.schedule(new Runnable () {
            public void run() {
                timedOut(activePlayer);
            }
        }, TIME_LIMIT_IN_MILLIS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Schedules a time limit for a player before they are removed from the game.
     * @param playerID unique ID of the player
     */
    public synchronized void scheduleInactivity(String playerID) {
        ScheduledFuture<?> inactiveResult = executor.schedule(new Runnable () {
            public void run() {
                removePlayer(playerID);
            }
        }, TIME_LIMIT_IN_MILLIS, TimeUnit.MILLISECONDS);
        playerTimeouts.put(playerID, inactiveResult);
    }
    
    /**
     * Cancels the removal of a player due to inactivity.
     * @param playerID unique ID of the player
     */
    public synchronized void cancelInactivity(String playerID) {
        if (playerTimeouts.keySet().contains(playerID)) {
            playerTimeouts.get(playerID).cancel(false);
        }
    }
    
    /**
     * Removes a player from the game.
     * @param playerID unique ID of the player
     */
    public synchronized void removePlayer(String playerID) {
        scores.remove(playerID);
        checkVotes(); // could be the case that if a player is removed, the votes for adding are now unanimous
        callListeners();
    }
    
    /** 
     * Allows a player to declare they've found a set, giving them rights to start picking 3 cards.
     * @param playerID the unique ID of the player
     */
    public synchronized void declareSet(String playerID) {
        if (activePlayer.equals(playerID) || declareQueue.contains(playerID)) { // clicking declare while declaring does nothing
            return; 
        } else if (!activePlayer.equals("")) { // another player is currently selecting cards
            declareQueue.add(playerID);
            return;
        } else {
            activePlayer = playerID;
            resetTimeout();
            scheduleTimeout();
        }
        callListeners();
    }
    
    /**
     * Retrieves the player currently finding a Set.
     * @return the current declarer
     */
    public synchronized String getDeclarer() {
        return activePlayer;
    }
    
    /**
     * Retrieves the Unix timestamp at which the declare will time out.
     * @return the timeout time, as a Unix timestamp
     */
    public synchronized long getTimeout() {
        return timeOut;
    }
    
    /**
     * Resets the timeout time to the current time, plus 5 seconds.
     */
    public synchronized void resetTimeout() {
        timeOut = System.currentTimeMillis() + TIME_LIMIT_IN_MILLIS; // gives 5 seconds to answer correctly
    }
    
    /**
     * Executes the condensing of 3 x (n+1) cards to 3 x n when n>4 and a Set is found.
     */
    public synchronized void condenseCards() {
        List<Card> cardsHeld = new ArrayList<>();
        int cols = getNumCols();
        
        for (int i=0; i<SET_SIZE; i++) {
            Square sq = squaresHeld.get(i);
            Card card = getCard(sq);
            cardsHeld.add(card);
        }
        
        List<Card> allCards = new ArrayList<>();
        for (int i=0; i<SET_SIZE; i++) { // remove the 3 cards found in the Set
            gameBoard.get(i).removeAll(cardsHeld);
            allCards.addAll(gameBoard.get(i));
        }
        
        int counter = 0;
        for (int i=0; i<DEFAULT_ROWS; i++) {
            List<Card> newRow = Collections.synchronizedList(new ArrayList<>());
            for (int j=0; j<cols-1; j++) {
                newRow.add(allCards.get(counter));
                counter += 1;
            }
            gameBoard.set(i, newRow);
        }
    }
    
    /**
     * Executes the replacement of three cards once they're found.
     */
    public synchronized void replaceCards() {
        if (cardsRemaining.size() == 0 || getNumCols() > defaultColumns) {
            condenseCards();
        } else {
            for (int i=0; i<SET_SIZE; i++) {
                Square sq = squaresHeld.get(i);
                Card newCard = cardsRemaining.remove();
                setCard(sq, newCard);
            }
        }
        if (cardsRemaining.size() == 0) {
            if (!existsSet()) {
                resetGame(generateRandomCards(defaultColumns));
            }
        }
        callListeners();
    }
    
    /**
     * Adds three cards to the board; called if no one can find a Set on the given board.
     */
    public synchronized void addCards() {
        for (int row=0; row<DEFAULT_ROWS; row++) {
            Card newCard = cardsRemaining.remove();
            gameBoard.get(row).add(newCard);
        }
    }
    
    /**
     * Allows a player to vote to add 3 more cards.
     * @param playerID the unique ID of the player
     */
    public synchronized void vote(String playerID) {
        if (cardsRemaining.size() == 0) { // shouldn't be able to add more cards if there are none left
            return; 
        }
        votes.add(playerID);
        checkVotes();
        callListeners();
    }
    
    /**
     * Checks to see if everyone has unanimously voted to add 3 more cards.
     */
    public synchronized void checkVotes() {
        if (votes.size() == numPlayers()) { // adds cards if all players agree
            addCards();
            votes.clear();
            
            if (cardsRemaining.size() == 0) {
                if (!existsSet()) {
                    resetGame(generateRandomCards(defaultColumns));
                }
            }
        }
    }
    
    /**
     * Gives the votes that have been cast for adding 3 more cards.
     * @return a copy of the set of votes
     */
    public synchronized Set<String> getVotes() {
        return new HashSet<>(votes);
    }
    
    /**
     * Finds how many players are playing.
     * @return the number of players in the game
     */
    public synchronized int numPlayers() {
        return scores.keySet().size();
    }
    
    /**
     * Executes if the player has run out of time to find a set.
     * @param playerID the unique ID of the player
     */
    public synchronized void timedOut(String playerID) {
        int score = scores.get(playerID);
        final int pointsLost = 5;
        scores.put(playerID, score-pointsLost);
        
        squaresHeld.clear();
        if (declareQueue.size() > 0) {
            activePlayer = declareQueue.remove();
            resetTimeout();
            scheduleTimeout();
        } else {
            activePlayer = "";
        }
        callListeners();
    }
    
    /**
     * Performs the selection of a card by a particular player, according to the rules of Set.
     * @param square the coordinates of the card the player wants to flip
     * @param playerID the unique of the player
     * @throws InterruptedException
     */
    public synchronized void pickCard(Square square, String playerID) throws InterruptedException {
        if (!playerID.equals(activePlayer)) { // cannot pick card if not currently the player picking cards
            return; 
        }
        if (squaresHeld.contains(square)) { // toggle if card already selected is picked again
            squaresHeld.remove(square);
            return;
        }
        
        squaresHeld.add(square);
        
        final int pointsWon = 10; // gain 10 points for a correct set
        final int pointsLost = 5; // lose 5 points for an incorrect set
        
        if (squaresHeld.size() == SET_SIZE) {
            result.cancel(false);
            int score = scores.get(playerID);
            if (checkSet()) {
                scores.put(playerID, score + pointsWon);
                votes.clear();
                declareQueue.clear();
                activePlayer = "";
                replaceCards();
            } else {
                scores.put(playerID, score - pointsLost);
                if (declareQueue.size() > 0) {
                    activePlayer = declareQueue.remove();
                    resetTimeout();
                } else {
                    activePlayer = "";
                }
            }
            // reset the board so the next player can find a Set
            squaresHeld.clear();
        }
        callListeners();
    }
    
    /**
     * Finds whether a Set exists on the remaining cards on the board.
     * @return whether a Set exists
     */
    public synchronized boolean existsSet() {
        List<Card> allCards = gameBoard.stream().flatMap(List::stream).collect(Collectors.toList());
        Set<Card> allCardsSet = new HashSet<>(allCards);
        for (int i=0; i<allCards.size(); i++) {
            for (int j=i+1; j<allCards.size(); j++) {
                Card missing = missingCard(allCards.get(i), allCards.get(j));
                if (allCardsSet.contains(missing)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * Given two cards, identifies the unique third card which will complete a Set.
     * @param card1
     * @param card2
     * @return the third card required to complete the Set
     */
    public synchronized Card missingCard(Card card1, Card card2) {
        // TODO can probably be DRYed but might be difficult using the existing enums
        Card.Color color;
        Card.Number number;
        Card.Shading shading;
        Card.Shape shape;
        
        if (card1.color().equals(card2.color())) {
            color = card1.color();
        } else {
            List<Card.Color> colors = new ArrayList<>(Arrays.asList(Card.Color.values()));
            colors.removeAll(List.of(card1.color(), card2.color()));
            color = colors.get(0);
        }
        
        if (card1.number().equals(card2.number())) {
            number = card1.number();
        } else {
            List<Card.Number> numbers = new ArrayList<>(Arrays.asList(Card.Number.values()));
            numbers.removeAll(List.of(card1.number(), card2.number()));
            number = numbers.get(0);
        }
        
        if (card1.shading().equals(card2.shading())) {
            shading = card1.shading();
        } else {
            List<Card.Shading> shadings = new ArrayList<>(Arrays.asList(Card.Shading.values()));
            shadings.removeAll(List.of(card1.shading(), card2.shading()));
            shading = shadings.get(0);
        }
        
        if (card1.shape().equals(card2.shape())) {
            shape = card1.shape();
        } else {
            List<Card.Shape> shapes = new ArrayList<>(Arrays.asList(Card.Shape.values()));
            shapes.removeAll(List.of(card1.shape(), card2.shape()));
            shape = shapes.get(0);
        }
        
        return new Card(color, number, shading, shape);
    }

}
