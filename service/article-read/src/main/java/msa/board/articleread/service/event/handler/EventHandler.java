package msa.board.articleread.service.event.handler;

import msa.board.common.event.Event;
import msa.board.common.event.EventPayload;

public interface EventHandler<T extends EventPayload> {
    void handle(Event<T> event);
    boolean supports(Event<T> event);
}
