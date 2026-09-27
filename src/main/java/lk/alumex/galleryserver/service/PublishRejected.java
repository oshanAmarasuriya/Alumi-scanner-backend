package lk.alumex.galleryserver.service;

/** A release the server will not accept, with the reason an operator needs to read. */
public class PublishRejected extends RuntimeException {
    public PublishRejected(String message) {
        super(message);
    }
}
