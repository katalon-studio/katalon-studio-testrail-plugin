/**
 * TestRail API binding for Java (API v2, available since TestRail 3.0)
 *
 * Learn more:
 *
 * http://docs.gurock.com/testrail-api2/start
 * http://docs.gurock.com/testrail-api2/accessing
 *
 * Copyright Gurock Software GmbH. See license.md for details.
 */

package com.gurock.testrail;
 
public class APIException extends Exception
{
	private int statusCode;

	public APIException(String message)
	{
		super(message);
	}

	public APIException(String message, int statusCode)
	{
		super(message);
		this.statusCode = statusCode;
	}

	public int getStatusCode()
	{
		return statusCode;
	}
}
