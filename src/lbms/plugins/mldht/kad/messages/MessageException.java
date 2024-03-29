package lbms.plugins.mldht.kad.messages;

import lbms.plugins.mldht.kad.messages.ErrorMessage.ErrorCode;

public class MessageException extends Exception {
	
	/*
	201	Generic Error
	202	Server Error
	203	Protocol Error, such as a malformed packet, invalid arguments, or bad token
	204	Method Unknown
	*/
	public ErrorCode errorCode = ErrorCode.GenericError;
	
	
	
	public MessageException(String message) {
		super(message);
	}
	
	public MessageException(String message, Throwable cause) {
		super(message,cause);
	}
	
	public MessageException(String message, Throwable cause, ErrorCode c) {
		super(message,cause);
		this.errorCode = c;
	}

	public MessageException(String message, ErrorCode code) {
		super(message);
		this.errorCode = code;
	}

	
}
