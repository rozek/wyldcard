package com.defano.wyldcard.parts.stack;

import com.defano.hypertalk.ast.model.*;
import com.defano.hypertalk.exception.HtException;
import com.defano.hypertalk.exception.HtSemanticException;
import com.defano.wyldcard.NavigationManager;
import com.defano.wyldcard.WyldCard;
import com.defano.wyldcard.aspect.RunOnDispatch;
import com.defano.wyldcard.fx.CurtainManager;
import com.defano.wyldcard.parts.Part;
import com.defano.wyldcard.parts.bkgnd.BackgroundModel;
import com.defano.wyldcard.parts.card.CardModel;
import com.defano.wyldcard.parts.card.CardPart;
import com.defano.wyldcard.parts.model.WyldCardPropertiesModel;
import com.defano.wyldcard.parts.model.PartModel;
import com.defano.wyldcard.parts.model.PropertyChangeObserver;
import com.defano.wyldcard.runtime.context.ExecutionContext;
import com.defano.wyldcard.util.ThreadUtils;
import com.defano.wyldcard.window.layouts.StackWindow;
import io.reactivex.Observable;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;

import java.awt.*;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Represents the controller object of the stack itself. See {@link StackModel} for the data model.
 * <p>
 * This controller is "virtual" because a stack has no view of its own, aside from the card that is currently displayed
 * in it. Thus, this class has no associated Swing component and cannot be added to a view hierarchy.
 */
public class StackPart implements Part, PropertyChangeObserver {

    private final NavigationManager navigationManager = WyldCard.getInstance().getNavigationManager();

    private final StackModel stackModel;
    private final CurtainManager curtainManager = new CurtainManager();
    private final Set<StackObserver> stackObservers = new HashSet<>();
    private final Set<StackNavigationObserver> stackNavigationObservers = new HashSet<>();
    private final Subject<Integer> cardCountProvider = BehaviorSubject.createDefault(0);
    private final Subject<Optional<CardPart>> cardClipboardProvider = BehaviorSubject.createDefault(Optional.empty());
    private CardPart currentCard;

    private StackPart(StackModel stackModel) {
        this.stackModel = stackModel;
    }

    public static StackPart newStack(ExecutionContext context) {
        return fromStackModel(context, StackModel.newStackModel("Untitled"));
    }

    public static StackPart fromStackModel(ExecutionContext context, StackModel model) {
        StackPart stackPart = new StackPart(model);

        stackPart.cardCountProvider.onNext(model.getCardCount());
        stackPart.stackModel.addPropertyChangedObserver(stackPart);

        try {
            stackPart.currentCard = CardPart.fromPositionInStack(context, model.getCurrentCardIndex(), model);
        } catch (HtException e) {
            throw new RuntimeException("Failed to create card.");
        }

        return stackPart;
    }

    /**
     * "Opens" this stack inside of a given window.
     * <p>
     * Sets up connections between the window controller and stack controller, displays the stack's current card and
     * sends the 'openStack' and 'openCard' message to the stack.
     *
     * @param stackWindow The window to bind this stack to
     */
    public void bindToWindow(StackWindow stackWindow) {

        // Make the window aware of us
        ExecutionContext context = new ExecutionContext(this);
        stackWindow.bindModel(this);

        // Display the current card
        navigationManager.goCard(context, this, stackModel.getCurrentCardIndex(), null, false);

        // Resize the window to fit this stack
        fireOnCardDimensionChanged(stackModel.getDimension(context));
    }

    /**
     * Gets the data model associated with this stack.
     *
     * @return The stack model.
     */
    public StackModel getStackModel() {
        return stackModel;
    }

    /**
     * Deletes the current card provided there are more than one card in the stack.
     *
     * @param context The execution context.
     * @return The card now visible in the stack window, or null if the current card could not be deleted.
     */
    @RunOnDispatch
    public CardPart deleteCard(ExecutionContext context) {
        if (canDeleteCard(context)) {
            WyldCard.getInstance().getToolsManager().setIsEditingBackground(false);

            int deletedCardIndex = stackModel.getCurrentCardIndex();
            stackModel.deleteCardModel();
            cardCountProvider.onNext(stackModel.getCardCount());
            fireOnCardOrderChanged();

            return activateCard(context, deletedCardIndex == 0 ? 0 : deletedCardIndex - 1);
        }

        WyldCard.getInstance().showErrorDialog(new HtSemanticException("This card cannot be deleted because it or its background is marked as \"Can't Delete\"."));
        return null;
    }

    /**
     * Creates a new card with a new background. Differs from {@link #newCard(ExecutionContext)} in that {@link #newCard(ExecutionContext)} creates a
     * new card with the same background as the current card.
     *
     * @param context The execution context.
     * @return The newly created card.
     */
    @RunOnDispatch
    public CardPart newBackground(ExecutionContext context) {
        WyldCard.getInstance().getToolsManager().setIsEditingBackground(false);

        stackModel.newCardWithNewBackground();
        cardCountProvider.onNext(stackModel.getCardCount());
        fireOnCardOrderChanged();

        return navigationManager.goNextCard(context, this, null);
    }

    /**
     * Creates a new card with the same background as the current card. See {@link #newBackground(ExecutionContext)} to create a new
     * card with a new background.
     *
     * @param context The execution context.
     * @return The newly created card.
     */
    @RunOnDispatch
    public CardPart newCard(ExecutionContext context) {
        WyldCard.getInstance().getToolsManager().setIsEditingBackground(false);

        stackModel.newCard(currentCard.getCardModel().getBackgroundId());
        cardCountProvider.onNext(stackModel.getCardCount());
        fireOnCardOrderChanged();

        return navigationManager.goNextCard(context, this, null);
    }

    /**
     * Removes the current card from the stack and places it into the card clipboard (for pasting elsewhere in the
     * stack).
     *
     * @param context The execution context.
     */
    @RunOnDispatch
    public void cutCard(ExecutionContext context) {
        cardClipboardProvider.onNext(Optional.of(getDisplayedCard()));
        cardCountProvider.onNext(stackModel.getCardCount());

        deleteCard(context);
    }

    /**
     * Copies the displayed card to the card clipboard for pasting elsewhere in the stack.
     */
    @RunOnDispatch
    public void copyCard() {
        cardClipboardProvider.onNext(Optional.of(getDisplayedCard()));
    }

    /**
     * Adds the card presently held in the card clipboard to the stack in the current card's position. Has no affect
     * if the clipboard is empty.
     *
     * @param context The execution context.
     */
    @RunOnDispatch
    public void pasteCard(ExecutionContext context) {
        if (cardClipboardProvider.blockingFirst().isPresent()) {
            WyldCard.getInstance().getToolsManager().setIsEditingBackground(false);

            CardModel card = cardClipboardProvider.blockingFirst().get().getCardModel().copyOf();
            card.relinkParentPartModel(getStackModel());
            card.newProperty(CardModel.PROP_ID, new Value(getStackModel().getNextCardId()), true);

            stackModel.insertCard(card);
            cardCountProvider.onNext(stackModel.getCardCount());
            fireOnCardOrderChanged();

            navigationManager.goNextCard(context, this, null);
        }
    }

    /**
     * Gets an observable object containing the contents of the card clipboard.
     *
     * @return The card clipboard provider.
     */
    public Observable<Optional<CardPart>> getCardClipboardProvider() {
        return cardClipboardProvider;
    }

    /**
     * Gets the currently displayed card.
     *
     * @return The current card
     */
    public CardPart getDisplayedCard() {
        return currentCard;
    }

    /**
     * Invalidates the card cache; useful only if modifying this stack's underlying stack model (i.e., as a
     * result of card sorting or re-ordering).
     *
     * @param context   The execution context.
     * @param cardIndex - The index of the card in the stack to transition to after invalidating the cache.
     */
    @RunOnDispatch
    public void invalidateCache(ExecutionContext context, int cardIndex) {
        this.currentCard.partClosed(context);
        this.currentCard = loadCard(context, getStackModel().getCurrentCardIndex());

        this.cardCountProvider.onNext(stackModel.getCardCount());

        fireOnCardOrderChanged();
        navigationManager.goCard(context, this, cardIndex, null, false);
    }

    /**
     * Gets an observable object containing the number of card in the stack.
     *
     * @return The card count provider
     */
    public Observable<Integer> getCardCountProvider() {
        return cardCountProvider;
    }

    /**
     * Adds an observer of stack changes.
     *
     * @param observer The observer
     */
    public void addObserver(StackObserver observer) {
        stackObservers.add(observer);
    }

    /**
     * Removes an observer of stack changes.
     *
     * @param observer The observer
     */
    public void removeObserver(StackObserver observer) {
        stackObservers.remove(observer);
    }

    /**
     * Adds an observer of stack navigation changes (i.e., user changed cards)
     *
     * @param observer The observer
     */
    public void addNavigationObserver(StackNavigationObserver observer) {
        stackNavigationObservers.add(observer);
    }

    /**
     * Removes an observer of stack navigation changes.
     *
     * @param observer The observer to remove
     */
    public void removeNavigationObserver(StackNavigationObserver observer) {
        stackNavigationObservers.remove(observer);
    }

    public CurtainManager getCurtainManager() {
        return curtainManager;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @RunOnDispatch
    public void onPropertyChanged(ExecutionContext context, WyldCardPropertiesModel model, String property, Value oldValue, Value newValue) {
        switch (property) {
            case StackModel.PROP_NAME:
                fireOnStackNameChanged(newValue.toString());
                break;

            case StackModel.PROP_HEIGHT:
            case StackModel.PROP_WIDTH:
                // Resize the window
                fireOnCardDimensionChanged(getStackModel().getDimension(context));

                // Re-load the card model into the size
                activateCard(context, stackModel.getCurrentCardIndex());
                break;

            case StackModel.PROP_RESIZABLE:
                WyldCard.getInstance().getWindowManager().getWindowForStack(context, context.getCurrentStack()).setAllowResizing(newValue.booleanValue());
                break;
        }
    }

    /**
     * Loads the requested card in the stack, allowing the card to initialize itself before being returned. Note that
     * this only creates the card controller object (it does not display it in the stack window); use
     * {@link com.defano.wyldcard.NavigationManager} to navigate to and display the card.
     *
     * @param context   The execution context
     * @param cardIndex The index of the card in this stack (0-based) to load.
     * @return The loaded and initialized card
     */
    @RunOnDispatch
    public CardPart loadCard(ExecutionContext context, int cardIndex) {
        try {
            CardPart card = CardPart.fromPositionInStack(context, cardIndex, stackModel);
            card.partOpened(context);
            return card;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load card.", e);
        }
    }

    /**
     * Closes the current card by deactivating paint tools on its canvas and notifying observers that the card is being
     * closed.
     *
     * @param context The execution context
     * @param push    True to indicate this card should be added to the backstack
     */
    @RunOnDispatch
    public void deactivateCard(ExecutionContext context, boolean push) {
        CardPart displayedCard = getDisplayedCard();

        // Deactivate paint tool before doing anything (to commit in-fight changes)
        WyldCard.getInstance().getToolsManager().getPaintTool().deactivate();

        // Stop editing background when card changes
        WyldCard.getInstance().getToolsManager().setIsEditingBackground(false);

        // When requested, push the current card onto the backstack
        if (push) {
            Destination destination = new Destination(this.getStackModel(), displayedCard.getId(context));
            WyldCard.getInstance().getStackManager().getBackstack().push(destination);
        }

        // Notify observers that current card is going away
        fireOnCardClosing(displayedCard);
        displayedCard.partClosed(context);
    }

    /**
     * Loads and activates the identified card in the stack (that is, the requested card becomes the card that the user
     * is currently interacting with).
     *
     * @param context   The execution context
     * @param cardIndex The index (0-based) of the card to activate in this stack
     * @return The activated card
     */
    @RunOnDispatch
    public CardPart activateCard(ExecutionContext context, int cardIndex) {

        try {
            // Change card
            stackModel.setCurrentCardIndex(cardIndex);
            currentCard = loadCard(context, cardIndex);

            // Notify observers of new card
            fireOnCardOpened(currentCard);

            // Reactivate paint tool on new card's canvas
            WyldCard.getInstance().getToolsManager().reactivateTool(currentCard.getCanvas());

            return currentCard;

        } catch (Exception e) {
            throw new RuntimeException("Failed to activate card.", e);
        }
    }

    private boolean canDeleteCard(ExecutionContext context) {
        long cardCountInBackground = stackModel.getCardsInBackground(getDisplayedCard().getCardModel().getBackgroundId()).size();

        return stackModel.getCardCount() > 1 &&
                !getDisplayedCard().getCardModel().getKnownProperty(context, CardModel.PROP_CANTDELETE).booleanValue() &&
                (cardCountInBackground > 1 || !getDisplayedCard().getCardModel().getBackgroundModel().getKnownProperty(context, BackgroundModel.PROP_CANTDELETE).booleanValue());
    }

    private void fireOnStackOpened() {
        ThreadUtils.invokeAndWaitAsNeeded(() -> {
            for (StackObserver observer : stackObservers) {
                observer.onStackOpened(StackPart.this);
            }
        });
    }

    private void fireOnCardClosing(CardPart closingCard) {
        ThreadUtils.invokeAndWaitAsNeeded(() -> {
            for (StackNavigationObserver observer : stackNavigationObservers) {
                observer.onCardClosed(closingCard);
            }
        });
    }

    private void fireOnCardOpened(CardPart openedCard) {
        ThreadUtils.invokeAndWaitAsNeeded(() -> {
            for (StackNavigationObserver observer : stackNavigationObservers) {
                observer.onCardOpened(openedCard);
            }
        });
    }

    private void fireOnCardDimensionChanged(Dimension newDimension) {
        ThreadUtils.invokeAndWaitAsNeeded(() -> {
            for (StackObserver observer : stackObservers) {
                observer.onStackDimensionChanged(newDimension);
            }
        });
    }

    private void fireOnStackNameChanged(String newName) {
        ThreadUtils.invokeAndWaitAsNeeded(() -> {
            for (StackObserver observer : stackObservers) {
                observer.onStackNameChanged(newName);
            }
        });
    }

    private void fireOnCardOrderChanged() {
        ThreadUtils.invokeAndWaitAsNeeded(() -> {
            for (StackObserver observer : stackObservers) {
                observer.onCardOrderChanged();
            }
        });
    }

    @Override
    public PartType getType() {
        return PartType.STACK;
    }

    @Override
    public PartModel getPartModel() {
        return getStackModel();
    }

    @Override
    public void partOpened(ExecutionContext context) {
        currentCard = loadCard(context, getStackModel().getCurrentCardIndex());
        getStackModel().receiveMessage(context.bind(this), SystemMessage.OPEN_STACK.messageName);

        fireOnCardOpened(getDisplayedCard());
        fireOnStackOpened();
    }

    @Override
    public void partClosed(ExecutionContext context) {
        deactivateCard(context, false);
    }
}
