/*
 * Copyright 2000-2022 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.client;

import java.util.Objects;

import com.google.web.bindery.event.shared.HandlerRegistration;

import com.google.gwt.core.client.JavaScriptException;
import com.google.gwt.core.client.JsDate;
import com.vaadin.client.flow.collection.JsArray;
import com.vaadin.client.flow.collection.JsCollections;

import elemental.client.Browser;
import elemental.events.Event;
import elemental.events.PopStateEvent;
import elemental.html.History;
import elemental.html.Window;
import elemental.json.Json;
import elemental.json.JsonArray;
import elemental.json.JsonObject;

/**
 * Handler for restoring scroll position when user navigates back / forward
 * inside the application. This is used instead of browser's native
 * <code>history.scrollRestoration = "auto"</code> since it won't work with
 * content generated by JavaScript.
 * <p>
 * Uses {@link History#getState() History.state} to store history indexes and a
 * token. This is done because need to know which index in the scroll position
 * arrays does each history entry map to. The token used to identify the correct
 * scroll positions array.
 * <p>
 * Uses {@link Window#getSessionStorage() window.sessionStorage} to store the
 * actual scroll position arrays. This is used so that the scroll positions can
 * be always restored, even when navigating from outside back into a middle of
 * the history chain. The arrays are stored using the token so that they can be
 * linked to a specific history index.
 *
 * @author Vaadin Ltd
 * @since 1.0
 */
public class ScrollPositionHandler {

    private static final String MISSING_STATE_VARIABLES_MESSAGE = "Unable to restore scroll positions. History.state has been manipulated or user has navigated away from site in an unrecognized way.";

    /**
     * Key used to store history index into History.state.
     */
    private static final String HISTORY_INDEX = "historyIndex";

    /**
     * Key used to store history reset token into History.state;
     */
    private static final String HISTORY_TOKEN = "historyResetToken";

    /**
     * Key used to store X positions into History.state.
     */
    private static final String X_POSITIONS = "xPositions";

    /**
     * Key used to store Y positions into History.state.
     */
    private static final String Y_POSITIONS = "yPositions";

    private final Registry registry;

    private JsArray<Double> xPositions;
    private JsArray<Double> yPositions;

    private HandlerRegistration responseHandlingEndedHandler;

    /**
     * The current index in the scroll position arrays.
     */
    private int currentHistoryIndex;

    /**
     * Unique identifier used to bind scroll position arrays and history index
     * together. Used to avoid case of applying wrong scroll positions to a
     * history state with the same index.
     */
    private double historyResetToken;

    private boolean ignoreScrollRestorationOnNextPopStateEvent;

    /**
     * Creates a new instance connected to the given registry.
     *
     * @param registry
     *            the global registry
     */
    public ScrollPositionHandler(Registry registry) {
        this.registry = registry;

        // use custom scroll restoration instead of browser implementation
        disableNativeScrollRestoration();

        // before leaving the app need to store scroll positions to state
        Browser.getWindow().addEventListener("beforeunload",
                this::onBeforeUnload);

        readAndRestoreScrollPositionsFromHistoryAndSessionStorage(true);
    }

    /**
     * Default constructor to use in subclasses to override all functionality
     * from this class.
     */
    protected ScrollPositionHandler() {
        this.registry = null;
    }

    private void readAndRestoreScrollPositionsFromHistoryAndSessionStorage(
            boolean delayAfterResponse) {
        // restore history index & token from state if applicable
        JsonObject state = (JsonObject) Browser.getWindow().getHistory()
                .getState();
        if (state != null && state.hasKey(HISTORY_INDEX)
                && state.hasKey(HISTORY_TOKEN)) {

            currentHistoryIndex = (int) state.getNumber(HISTORY_INDEX);
            historyResetToken = state.getNumber(HISTORY_TOKEN);

            String jsonString = null;
            try {
                jsonString = Browser.getWindow().getSessionStorage()
                        .getItem(createSessionStorageKey(historyResetToken));
            } catch (JavaScriptException e) {
                Console.error(
                        "Failed to get session storage: " + e.getMessage());
            }
            if (jsonString != null) {
                JsonObject jsonObject = Json.parse(jsonString);

                xPositions = convertJsonArrayToArray(
                        jsonObject.getArray(X_POSITIONS));
                yPositions = convertJsonArrayToArray(
                        jsonObject.getArray(Y_POSITIONS));
                // array lengths checked in restoreScrollPosition
                restoreScrollPosition(delayAfterResponse);
            } else {
                Console.warn(
                        "History.state has scroll history index, but no scroll positions found from session storage matching token <"
                                + historyResetToken
                                + ">. User has navigated out of site in an unrecognized way.");
                resetScrollPositionTracking();
            }
        } else {
            resetScrollPositionTracking();
        }
    }

    private void resetScrollPositionTracking() {
        xPositions = JsCollections.array();
        yPositions = JsCollections.array();
        currentHistoryIndex = 0;
        historyResetToken = JsDate.now();
    }

    private void onBeforeUnload(Event event) {
        captureCurrentScrollPositions();

        JsonObject stateObject = createStateObjectWithHistoryIndexAndToken();

        JsonObject sessionStorageObject = Json.createObject();
        sessionStorageObject.put(X_POSITIONS,
                convertArrayToJsonArray(xPositions));
        sessionStorageObject.put(Y_POSITIONS,
                convertArrayToJsonArray(yPositions));

        Browser.getWindow().getHistory().replaceState(stateObject, "",
                Browser.getWindow().getLocation().getHref());
        try {
            Browser.getWindow().getSessionStorage().setItem(
                    createSessionStorageKey(historyResetToken),
                    sessionStorageObject.toJson());
        } catch (JavaScriptException e) {
            Console.error("Failed to get session storage: " + e.getMessage());
        }
    }

    private static String createSessionStorageKey(Number historyToken) {
        return "scrollPos-" + historyToken;
    }

    /**
     * Store scroll positions and restore scroll positions depending on the
     * given pop state event.
     * <p>
     * This method behaves differently if there has been a
     * {@link #beforeClientNavigation(String)} before this, and if the pop state
     * event is caused by a fragment change that doesn't require a server side
     * round-trip.
     *
     * @param event
     *            the pop state event
     * @param triggersServerSideRoundtrip
     *            <code>true</code> if the pop state event triggers a server
     *            side request, <code>false</code> if not
     */
    public void onPopStateEvent(PopStateEvent event,
            boolean triggersServerSideRoundtrip) {
        if (ignoreScrollRestorationOnNextPopStateEvent) {
            Browser.getWindow().getHistory().replaceState(
                    createStateObjectWithHistoryIndexAndToken(), "",
                    Browser.getDocument().getLocation().getHref());

            ignoreScrollRestorationOnNextPopStateEvent = false;
            return;
        }

        captureCurrentScrollPositions();

        JsonObject state = (JsonObject) event.getState();
        if (state == null || !state.hasKey(HISTORY_INDEX)
                || !state.hasKey(HISTORY_TOKEN)) {
            // state doesn't contain history index info, just log & reset
            Console.warn(MISSING_STATE_VARIABLES_MESSAGE);
            resetScrollPositionTracking();
            return;
        }

        double token = state.getNumber(HISTORY_TOKEN);
        if (!Objects.equals(token, historyResetToken)) {
            // current scroll positions are not for this history entry
            // try to restore arrays or reset
            readAndRestoreScrollPositionsFromHistoryAndSessionStorage(
                    triggersServerSideRoundtrip);
            return;
        }

        currentHistoryIndex = (int) state.getNumber(HISTORY_INDEX);
        restoreScrollPosition(triggersServerSideRoundtrip);
    }

    /**
     * Tells this scroll handler that the next call to
     * {@link #onPopStateEvent(PopStateEvent, boolean)} should not try to
     * restore scroll position.
     * <p>
     * There are differences between browsers on which order the pop state and
     * fragment change events are fired. This is just to recognize the case
     * where a fragment change event fired by framework (to get scroll to
     * fragment) causes a pop state event that should be ignored here.
     *
     * @param ignoreScrollRestorationOnNextPopStateEvent
     *            <code>true</code> to NOT restore scroll on pop state event,
     *            <code>false</code> to restore
     */
    public void setIgnoreScrollRestorationOnNextPopStateEvent(
            boolean ignoreScrollRestorationOnNextPopStateEvent) {
        this.ignoreScrollRestorationOnNextPopStateEvent = ignoreScrollRestorationOnNextPopStateEvent;
    }

    /**
     * Store scroll positions when there has been navigation triggered by a
     * click on a link element and no server round-trip is needed. It means
     * navigating within the same page.
     * <p>
     * If href for the page navigated into contains a hash (even just #), then
     * the browser will fire a pop state event afterwards.
     *
     * @param newHref
     *            the href of the clicked link
     */
    public void beforeClientNavigation(String newHref) {
        captureCurrentScrollPositions();

        Browser.getWindow().getHistory().replaceState(
                createStateObjectWithHistoryIndexAndToken(), "",
                Browser.getWindow().getLocation().getHref());

        // move to page top only if there is no fragment so scroll position
        // doesn't bounce around
        if (!newHref.contains("#")) {
            resetScroll();
        }

        currentHistoryIndex++;

        // remove old stored scroll positions
        xPositions.splice(currentHistoryIndex,
                xPositions.length() - currentHistoryIndex);
        yPositions.splice(currentHistoryIndex,
                yPositions.length() - currentHistoryIndex);
    }

    private void resetScroll() {
        setScrollPosition(new double[] { 0, 0 });
    }

    private void captureCurrentScrollPositions() {
        double[] xAndYPosition = getScrollPosition();

        xPositions.set(currentHistoryIndex, xAndYPosition[0]);
        yPositions.set(currentHistoryIndex, xAndYPosition[1]);
    }

    private JsonObject createStateObjectWithHistoryIndexAndToken() {
        JsonObject state = Json.createObject();
        state.put(HISTORY_INDEX, currentHistoryIndex);
        state.put(HISTORY_TOKEN, historyResetToken);
        return state;
    }

    /**
     * Store scroll positions when there has been navigation triggered by a
     * click on a link element and a server round-trip was needed. This method
     * is called after server-side part is done.
     *
     * @param state
     *            includes scroll position of the previous page and the complete
     *            href of the router link that was clicked and caused this
     *            navigation
     */
    public void afterServerNavigation(JsonObject state) {
        if (!state.hasKey("scrollPositionX") || !state.hasKey("scrollPositionY")
                || !state.hasKey("href"))
            throw new IllegalStateException(
                    "scrollPositionX, scrollPositionY and href should be available in ScrollPositionHandler.afterNavigation.");
        xPositions.set(currentHistoryIndex, state.getNumber("scrollPositionX"));
        yPositions.set(currentHistoryIndex, state.getNumber("scrollPositionY"));

        Browser.getWindow().getHistory().replaceState(
                createStateObjectWithHistoryIndexAndToken(), "",
                Browser.getWindow().getLocation().getHref());

        String newHref = state.getString("href");
        // move to page top only if there is no fragment so scroll position
        // doesn't bounce around
        if (!newHref.contains("#")) {
            resetScroll();
        }

        currentHistoryIndex++;

        // store new index
        Browser.getWindow().getHistory().pushState(
                createStateObjectWithHistoryIndexAndToken(), "", newHref);

        // remove old stored scroll positions
        xPositions.splice(currentHistoryIndex,
                xPositions.length() - currentHistoryIndex);
        yPositions.splice(currentHistoryIndex,
                yPositions.length() - currentHistoryIndex);
    }

    private void restoreScrollPosition(boolean delayAfterResponse) {
        // in case there is another event while waiting for response (?)
        if (responseHandlingEndedHandler != null) {
            responseHandlingEndedHandler.removeHandler();
        }

        if (currentHistoryIndex >= xPositions.length()
                || currentHistoryIndex >= yPositions.length()) {
            Console.warn("No matching scroll position found (entries X:"
                    + xPositions.length() + ", Y:" + yPositions.length()
                    + ") for opened history index (" + currentHistoryIndex
                    + "). " + MISSING_STATE_VARIABLES_MESSAGE);
            resetScrollPositionTracking();
            return;
        }

        int scrollX = xPositions.get(currentHistoryIndex).intValue();
        int scrollY = yPositions.get(currentHistoryIndex).intValue();

        if (delayAfterResponse) {
            responseHandlingEndedHandler = registry.getRequestResponseTracker()
                    .addResponseHandlingEndedHandler(
                            responseHandlingEndedEvent -> {
                                setScrollPosition(
                                        new double[] { scrollX, scrollY });
                                responseHandlingEndedHandler.removeHandler();
                            });
        } else {
            setScrollPosition(new double[] { scrollX, scrollY });
        }
    }

    private static JsArray<Double> convertJsonArrayToArray(
            JsonArray jsonArray) {
        return WidgetUtil.crazyJsCast(jsonArray);
    }

    private static JsonArray convertArrayToJsonArray(JsArray<Double> array) {
        return WidgetUtil.crazyJsCast(array);
    }

    private static native void disableNativeScrollRestoration()
    /*-{
       if ('scrollRestoration' in history) {
           history.scrollRestoration = "manual";
       }
    }-*/;

    /**
     * Gets the scroll position of the page as an array.
     * 
     * @return an array containing scroll position x (left) and y (top) in order
     */
    public static native double[] getScrollPosition()
    /*-{
        if ($wnd.Vaadin.Flow.getScrollPosition) {
          return $wnd.Vaadin.Flow.getScrollPosition();
        } else {
          // window.pageX/YOffset is an alias for scrollX/Y but also works in IE11
          return [$wnd.pageXOffset, $wnd.pageYOffset];
        }
    }-*/;

    // double[] here as well to maintain consistency with the getter
    private static native void setScrollPosition(double[] xAndYPosition)
    /*-{
        if ($wnd.Vaadin.Flow.setScrollPosition) {
          $wnd.Vaadin.Flow.setScrollPosition(xAndYPosition);
        } else {
          $wnd.scrollTo(xAndYPosition[0], xAndYPosition[1]);
        }
    }-*/;
}
