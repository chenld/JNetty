/*
 * Copyright (c) 1998-2012 Caucho Technology -- all rights reserved
 *
 * This file is part of Resin(R) Open Source
 *
 * Each copy or derived work must preserve the copyright notice and this
 * notice unmodified.
 *
 * Resin Open Source is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * Resin Open Source is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, or any warranty
 * of NON-INFRINGEMENT.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Resin Open Source; if not, write to the
 *
 *   Free Software Foundation, Inc.
 *   59 Temple Place, Suite 330
 *   Boston, MA 02111-1307  USA
 *
 * @author Scott Ferguson
 */

package org.ireland.jnetty.webapp;

import java.io.CharArrayWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Locale;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.Log;

import javax.servlet.AsyncContext;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.ServletResponse;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.caucho.VersionFactory;
import com.caucho.config.LineException;
import com.caucho.env.shutdown.ShutdownSystem;
import com.caucho.i18n.CharacterEncoding;
import com.caucho.java.LineMap;
import com.caucho.java.LineMapException;
import com.caucho.java.ScriptStackTrace;
import com.caucho.server.dispatch.BadRequestException;
import com.caucho.server.host.Host;
import com.caucho.server.http.CauchoRequest;
import com.caucho.server.http.CauchoResponse;
import com.caucho.server.http.HttpServletRequestImpl;
import com.caucho.server.http.HttpServletResponseImpl;
import com.caucho.util.CharBuffer;
import com.caucho.util.CompileException;
import com.caucho.util.CurrentTime;
import com.caucho.util.DisplayableException;

import com.caucho.util.LineCompileException;
import com.caucho.util.QDate;
import com.caucho.vfs.ClientDisconnectException;
import com.caucho.vfs.Encoding;
import com.caucho.vfs.Vfs;

/**
 * Represents the final servlet in a filter chain.
 */
public class ErrorPageManager
{
	private static final Log log = LogFactory.getLog(ErrorPageManager.class.getName());
	
	private static final boolean debug = log.isDebugEnabled();

	public static final char[] MSIE_PADDING;

	public static String JSP_EXCEPTION = "javax.servlet.jsp.jspException";

	public static String SHUTDOWN = "com.caucho.shutdown";

	private final Host _host;
	private final WebApp _webApp;

	private HashMap<Object, String> _errorPageMap = new HashMap<Object, String>();

	private String _defaultLocation;

	/**
	 * Create error page manager.
	 */
	public ErrorPageManager(WebApp webApp)
	{
		this(null, webApp);
	}

	/**
	 * Create error page manager.
	 */
	public ErrorPageManager(Host host, WebApp app)
	{
		_webApp = app;

		_host = host;
	}

	/**
	 * Adds an error page.
	 */
	public void addErrorPage(String exceptionType, String location)
	{
		_errorPageMap.put(exceptionType, location);

	}

	/**
	 * Adds an error page.
	 */
	public void addErrorPage(int errorCode, String location)
	{
		_errorPageMap.put(errorCode, location);
	}

	/**
	 * 设置默认的错误页面
	 * 
	 * @param location
	 */
	public void setDefaultLocation(String location)
	{
		this._defaultLocation = location;
	}

	/**
	 * Returns true if we should return a development-friendly error page.
	 */
	protected boolean isDevelopmentModeErrorPage()
	{
		return true;
	}

	/**
	 * Displays a parse error.
	 */
	public void sendServletError(Throwable e, ServletRequest req, ServletResponse res) throws IOException
	{
		try
		{
			sendServletErrorImpl(e, req, res);
		}
		finally
		{
			if (res instanceof CauchoResponse)
				((CauchoResponse) res).close();
		}
	}

	public void sendServletErrorImpl(Throwable e, ServletRequest req, ServletResponse res) throws IOException
	{
		HttpServletResponse response = (HttpServletResponse) res;
		HttpServletRequest request = (HttpServletRequest) req;
		Throwable rootExn = e;
		Throwable errorPageExn = null;
		LineMap lineMap = null;

		try
		{
			response.reset();
		}
		catch (IllegalStateException e1)
		{
		}

		if (req.isAsyncStarted())
		{
			AsyncContext async = req.getAsyncContext();

			if (async != null)
				async.complete();
		}

		if (response instanceof HttpServletResponseImpl)
		{
			HttpServletResponseImpl resFacade = (HttpServletResponseImpl) response;
			resFacade.killCache();
			resFacade.setNoCache(true);
		}

		if (rootExn instanceof ClientDisconnectException)
			throw (ClientDisconnectException) rootExn;

		String location = null;

		String title = "500 Servlet Exception";
		boolean isBadRequest = false;
		boolean doStackTrace = true;
		boolean isCompileException = false;
		boolean isServletException = false;
		Throwable compileException = null;
		String lineMessage = null;

		boolean lookupErrorPage = true;

		while (true)
		{
			if (rootExn instanceof LineMapException)
				lineMap = ((LineMapException) rootExn).getLineMap();

			if (lookupErrorPage)
			{
				errorPageExn = rootExn;
			}

			if (rootExn instanceof DisplayableException)
			{
				doStackTrace = false;
				isCompileException = true;
				if (compileException == null)
					compileException = rootExn;
			}
			else if (rootExn instanceof CompileException)
			{
				doStackTrace = false;
				isCompileException = true;

				if (compileException == null) // ! isLineCompileException)
					compileException = rootExn;
			}
			else if (rootExn instanceof LineException)
			{
				if (lineMessage == null)
					lineMessage = rootExn.getMessage();
			}

			if (rootExn instanceof BadRequestException)
			{
				isBadRequest = true;
			}

			if (rootExn instanceof OutOfMemoryError)
			{
				String msg = "TcpSocketLink OutOfMemory";

				ShutdownSystem.shutdownOutOfMemory(msg);
			}

			if (location != null || !lookupErrorPage)
			{
			}
			else if (rootExn instanceof LineMapException && rootExn instanceof ServletException && !(rootExn instanceof LineCompileException)
					&& rootExn.getCause() != null)
			{
				// hack to deal with JSP wrapping
			}
			else if (!isServletException)
			{
				// SRV.9.9.2 Servlet 2.4
				// location = getErrorPage(rootExn, ServletException.class);
				location = getErrorPage(rootExn);
				isServletException = true;
			}
			else
			{
				location = getErrorPage(rootExn);
				lookupErrorPage = false;
			}

			if (location != null)
				lookupErrorPage = false;

			if (isBadRequest)
				break;

			Throwable cause = null;
			if (rootExn instanceof ServletException && !(rootExn instanceof LineCompileException))
				cause = ((ServletException) rootExn).getRootCause();
			else
			{
				lookupErrorPage = false;
				cause = rootExn.getCause();
			}

			if (cause != null)
				rootExn = cause;
			else
			{
				break;
			}
		}

		if (location == null && lookupErrorPage)
		{
			location = getErrorPage(rootExn);
		}

		if (location == null)
			location = getErrorPage(500);

		if (isBadRequest)
		{
			// server/05a0, server/0532

			if (rootExn instanceof CompileException)
				title = rootExn.getMessage();
			else
				title = String.valueOf(rootExn);

			doStackTrace = false;
			isBadRequest = true;

			if (request instanceof CauchoRequest)
				((CauchoRequest) request).killKeepalive("bad request: " + rootExn);

			response.resetBuffer();

			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);

			/*
			 * if (location == null) log.warn(e.toString());
			 */
		}
		else if (rootExn instanceof UnavailableException)
		{
			UnavailableException unAvail = (UnavailableException) rootExn;

			if (unAvail.isPermanent())
			{
				response.setStatus(HttpServletResponse.SC_NOT_FOUND);
				title = "404 Not Found";

				if (location == null)
					location = getErrorPage(HttpServletResponse.SC_NOT_FOUND);
			}
			else
			{
				response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				title = "503 Unavailable";

				if (unAvail.getUnavailableSeconds() > 0)
					response.setIntHeader("Retry-After", unAvail.getUnavailableSeconds());

				if (location == null)
					location = getErrorPage(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			}
		}
		else
		{
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}

		if (location == null)
			location = _defaultLocation;


		if (log.isTraceEnabled())
			log.trace(e.toString(), e);
		else if (isCompileException)
		{
			if (isBadRequest)
				log.trace(BadRequestException.class.getSimpleName() + ": " + compileException.getMessage());
			else
				log.trace(compileException.getMessage());
		}
		else if (!doStackTrace)
			log.trace( rootExn.toString());
		else
			log.trace( e.toString(), e);

		if (location != null)
		{
			if (errorPageExn == null)
				errorPageExn = rootExn;

			request.setAttribute(JSP_EXCEPTION, errorPageExn);
			request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, errorPageExn);
			request.setAttribute(RequestDispatcher.ERROR_EXCEPTION_TYPE, errorPageExn.getClass());
			if (request instanceof HttpServletRequest)
				request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, ((HttpServletRequest) request).getRequestURI());

			String servletName = getServletName(request);

			if (servletName != null)
				request.setAttribute(RequestDispatcher.ERROR_SERVLET_NAME, servletName);

			request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, new Integer(500));
			request.setAttribute(RequestDispatcher.ERROR_MESSAGE, errorPageExn.getMessage());

			try
			{
				RequestDispatcher disp = null;
				// can't use filters because of error pages due to filters
				// or security.

				WebApp webApp = getWebApp();

				if (webApp != null)
					disp = webApp.getRequestDispatcher(location);
				else if (_host != null)
					disp = _host.getWebAppContainer().getRequestDispatcher(location);

				if (disp != null)
				{
					((RequestDispatcherImpl) disp).error(request, response);
					return;
				}
			}
			catch (Throwable e1)
			{
				log.info(e1.toString(), e1);
				rootExn = e1;
			}
		}

		response.setContentType("text/html");

		String encoding = CharacterEncoding.getLocalEncoding();

		if (encoding != null)
			response.setCharacterEncoding(encoding);
		else
		{
			Locale locale = Locale.getDefault();
			if (!"ISO-8859-1".equals(Encoding.getMimeName(locale)))
				response.setLocale(Locale.getDefault());
			else
				response.setCharacterEncoding("utf-8");
		}

		PrintWriter out;

		try
		{
			out = response.getWriter();
		}
		catch (IllegalStateException e1)
		{
			log.trace(e1.toString(), e1);

			out = new PrintWriter(new OutputStreamWriter(response.getOutputStream()));
		}

		if (isDevelopmentModeErrorPage())
		{
			out.println("<html>");
			if (!response.isCommitted())
				out.println("<head><title>" + escapeHtml(title) + "</title></head>");
			out.println("<body>");
			out.println("<h1>" + escapeHtml(title) + "</h1>");

			out.println("<code><pre>");

			if (debug && !CurrentTime.isTest())
				doStackTrace = true;

			if (doStackTrace)
			{
				out.println("<script language='javascript' type='text/javascript'>");
				out.println("function show() { document.getElementById('trace').style.display = ''; }");
				out.println("</script>");
				out.print("<a style=\"text-decoration\" href=\"javascript:show();\">[show]</a> ");
			}

			if (compileException instanceof DisplayableException)
			{
				// ioc/0000
				// XXX: dispExn.print doesn't normalize user.name
				// dispExn.print(out);
				out.println(escapeHtml(compileException.getMessage()));
			}
			else if (compileException != null)
				out.println(escapeHtml(compileException.getMessage()));
			else
				out.println(escapeHtml(rootExn.toString()));

			if (doStackTrace)
			{
				out.println("<span id=\"trace\" style=\"display:none\">");
				printStackTrace(out, lineMessage, e, rootExn, lineMap);
				out.println("</span>");
			}

			/*
			 * if (doStackTrace || debug) { printStackTrace(out, lineMessage, e, rootExn, lineMap); }
			 */

			out.println("</pre></code>");

			printVersion(out);

			out.println("</body></html>");
		}
		else
		{ // non-development mode
			out.println("<html>");
			out.println("<title>Server Error</title>");
			out.println("<body>");
			out.println("<h1>Server Error</h1>");
			out.println("<p>The server is temporarily unavailable due to an");
			out.println("internal error.  Please notify the system administrator");
			out.println("of this problem.</p>");

			out.println("<pre><code>");
			out.println("Date: " + QDate.formatISO8601(CurrentTime.getCurrentTime()));

			out.println("</code></pre>");

			printVersion(out);

			out.println("</body></html>");
		}

		String userAgent = request.getHeader("User-Agent");

		if (userAgent != null && userAgent.indexOf("MSIE") >= 0)
		{
			out.print(MSIE_PADDING);
		}

		out.close();
	}

	private void printVersion(PrintWriter out) throws IOException
	{
		String version = null;

		version = VersionFactory.getFullVersion();

		if (version != null)
		{
			out.println("<p /><hr />");
			out.println("<small>");

			out.println(version);

			out.println("</small>");
		}
	}

	private String getServletName(ServletRequest request)
	{
		if (request instanceof HttpServletRequestImpl)
			return ((HttpServletRequestImpl) request).getServletName();
		else if (request instanceof ServletRequestWrapper)
			return getServletName(((ServletRequestWrapper) request).getRequest());
		else
		{
			return null;
		}
	}

	/**
	 * Sends an HTTP error to the browser.
	 * 
	 * @param code
	 *            the HTTP error code
	 * @param message
	 *            a string message
	 */
	public void sendError(CauchoRequest request, CauchoResponse response, int code, String message) throws IOException
	{
		try
		{
			sendErrorImpl(request, response, code, message);
		}
		finally
		{
			response.close();
		}
	}

	/**
	 * Sends an HTTP error to the browser.
	 * 
	 * @param code
	 *            the HTTP error code
	 * @param message
	 *            a string message
	 */
	public void sendErrorImpl(CauchoRequest request,
                            CauchoResponse response,
                            int code, String message)
    throws IOException
  {
    response.resetBuffer();

    /* XXX: if we've already got an error, won't this just mask it?
    if (responseStream.isCommitted())
      throw new IllegalStateException("response can't sendError() after commit");
    */
    
    response.setStatus(code, message);

    try {
      if (handleErrorStatus(request, response, code, message)
          || code == HttpServletResponse.SC_NOT_MODIFIED) {
        return;
      }

      response.setContentType("text/html; charset=utf-8");
      boolean isOutputStreamWrapper = false;
      PrintWriter out;

      try {
        out = response.getWriter();
      } catch (IllegalStateException e) {
        log.debug( e.toString(), e);

        out = Vfs.openWrite(response.getOutputStream()).getPrintWriter();
        isOutputStreamWrapper = true;
      }

      out.println("<html>");
      if (! response.isCommitted()) {
        out.print("<head><title>");
        out.print(code);
        out.print(" ");
        out.print(escapeHtml(message));
        out.println("</title></head>");
      }

      out.println("<body>");
      out.print("<h1>");
      out.print(code);
      out.print(" ");
      out.print(escapeHtml(message));
      out.println("</h1>");

      if (code == HttpServletResponse.SC_NOT_FOUND) {
        out.println(escapeHtml(request.getPageURI())+" was not found on this server."
                        );
      }

      printVersion(out);
      
      out.println("</body></html>");

      String userAgent = request.getHeader("User-Agent");

      if (userAgent != null && userAgent.indexOf("MSIE") >= 0) {
        out.write(MSIE_PADDING, 0, MSIE_PADDING.length);
      }

      if (isOutputStreamWrapper) {
        out.flush();
        out.close();
      }
    } catch (Exception e) {
      log.warn( e.toString(), e);
    }
  }

	/**
	 * Handles an error status code.
	 * 
	 * @return true if we've forwarded to an error page.
	 */
	private boolean handleErrorStatus(CauchoRequest request, CauchoResponse response, int code, String message) throws ServletException, IOException
	{
		if (code == HttpServletResponse.SC_OK || code == HttpServletResponse.SC_MOVED_TEMPORARILY || code == HttpServletResponse.SC_NOT_MODIFIED)
			return false;

		if (request.getRequestDepth(0) > 16)
			return false;

		else if (request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) != null)
		{
			return false;
		}

		response.killCache();

		String location = getErrorPage(code);

		if (location == null)
			location = _defaultLocation;

		WebApp webApp = getWebApp();

		if (webApp == null && _host == null)
			return false;

		if (location != null && !location.equals(request.getRequestURI()))
		{
			request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, new Integer(code));
			request.setAttribute(RequestDispatcher.ERROR_MESSAGE, message);
			request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());

			String servletName = getServletName(request);

			if (servletName != null)
				request.setAttribute(RequestDispatcher.ERROR_SERVLET_NAME, servletName);

			try
			{
				RequestDispatcher disp = null;
				// can't use filters because of error pages due to filters
				// or security.
				if (webApp != null)
					disp = webApp.getRequestDispatcher(location);
				else if (_host != null)
					disp = _host.getWebAppContainer().getRequestDispatcher(location);

				// disp.forward(request, this, "GET", false);

				if (disp != null)
				{
					((RequestDispatcherImpl) disp).error(request, response);
				}
				else
					return false;
			}
			catch (Throwable e)
			{
				sendServletError(e, request, response);
			}

			return true;
		}

		return false;
	}

	/**
	 * Returns the URL of an error page for the given exception.
	 */
	String getErrorPage(Throwable e)
	{
		return getErrorPage(e, Throwable.class);
	}

	/**
	 * Returns the URL of an error page for the given exception.
	 */
	String getErrorPage(Throwable e, Class<?> limit)
	{
		Class<?> cl = e.getClass();
		for (; cl != null; cl = cl.getSuperclass())
		{
			String location = (String) _errorPageMap.get(cl.getName());
			if (location != null)
				return location;

			if (cl == limit)
				break;
		}

		for (cl = e.getClass(); cl != null; cl = cl.getSuperclass())
		{
			String name = cl.getName();
			int p = name.lastIndexOf('.');

			if (p > 0)
			{
				name = name.substring(p + 1);

				String location = (String) _errorPageMap.get(name);
				if (location != null)
					return location;
			}

			if (cl == limit)
				break;
		}

		return null;
	}

	private WebApp getWebApp()
	{
		return _webApp;
	}

	/**
	 * Returns the URL of an error page for the given exception.
	 */
	String getErrorPage(int code)
	{
		Integer key = new Integer(code);

		String location = (String) _errorPageMap.get(key);
		if (location != null)
			return location;

		return (String) _errorPageMap.get(new Integer(0));
	}

	/**
	 * Escapes HTML symbols in a stack trace.
	 */
	private void printStackTrace(PrintWriter out, String lineMessage, Throwable e, Throwable rootExn, LineMap lineMap)
	{
		CharArrayWriter writer = new CharArrayWriter();
		PrintWriter pw = new PrintWriter(writer);

		if (lineMessage != null)
			pw.println(lineMessage);

		if (lineMap != null)
			lineMap.printStackTrace(e, pw);
		else
			ScriptStackTrace.printStackTrace(e, pw);

		pw.close();

		char[] array = writer.toCharArray();
		out.print(escapeHtml(new String(array)));
	}

/**
   * Escapes special symbols in a string.  For example '<' becomes '&lt;'
   */
	private String escapeHtml(String s)
	{
		if (s == null)
			return null;

		if (CurrentTime.isTest())
		{
			s = normalizeForTesting(s);
		}

		CharBuffer cb = new CharBuffer();
		int lineCharacter = 0;
		boolean startsWithSpace = false;

		for (int i = 0; i < s.length(); i++)
		{
			char ch = s.charAt(i);

			lineCharacter++;

			if (ch == '<')
				cb.append("&lt;");
			else if (ch == '&')
				cb.append("&amp;");
			/*
			 * else if (ch == '%') cb.append("%25");
			 */
			else if (ch == '\n' || ch == '\r')
			{
				lineCharacter = 0;
				cb.append(ch);
				startsWithSpace = false;
			}
			else if (lineCharacter > 70 && ch == ' ' && !startsWithSpace)
			{
				lineCharacter = 0;
				cb.append('\n');
				for (; i + 1 < s.length() && s.charAt(i + 1) == ' '; i++)
				{
				}
			}
			else if (lineCharacter == 1 && (ch == ' ' || ch == '\t'))
			{
				cb.append((char) ch);
				startsWithSpace = true;
			}
			else
				cb.append(ch);
		}

		return cb.toString();
	}

	private String normalizeForTesting(String s)
	{
		String userName = System.getProperty("user.name");

		if ("caucho".equals(userName))
			return s;

		int p;

		while ((p = s.indexOf(userName)) >= 0)
		{
			String head = s.substring(0, p);
			String tail = s.substring(p + userName.length());

			s = head + "caucho" + tail;
		}

		return s;
	}

	public String toString()
	{
		return getClass().getSimpleName() + "[" + _webApp + "]";
	}

	static
	{
		MSIE_PADDING = ("\n\n\n\n" + "<!--\n" + "   - Because some older browsers replace their own messages\n"
				+ "   - to replace server error messages if the server\n" + "   - message is too short, it's necessary to pad out\n"
				+ "   - the error message to be at least 512 bytes.  With\n" + "   - this padding, Resin more informative error messages\n"
				+ "   - are available, making  debugging more straightforward.\n" + "   - \n" + "   - \n" + "   - Padding message repeats:\n" + "   - \n"
				+ "   - \n" + "   - Because some older browsers replace their own messages\n" + "   - to replace server error messages if the server\n"
				+ "   - message is too short, it's necessary to pad out\n" + "   - the error message to be at least 512 bytes.  With\n"
				+ "   - this padding, Resin more informative error messages\n" + "   - are available, making  debugging more straightforward.\n" + "   - \n"
				+ "   -->\n").toCharArray();
	}
}
