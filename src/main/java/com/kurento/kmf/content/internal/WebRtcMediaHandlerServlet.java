package com.kurento.kmf.content.internal;

import java.io.IOException;
import java.util.concurrent.Future;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.kurento.kmf.content.WebRtcMediaHandler;
import com.kurento.kmf.content.internal.jsonrpc.WebRtcJsonResponse;
import com.kurento.kmf.content.internal.jsonrpc.WebRtcJsonRequest;
import com.kurento.kmf.spring.KurentoApplicationContextUtils;

import static com.kurento.kmf.content.internal.jsonrpc.WebRtcJsonConstants.*;

@WebServlet(asyncSupported = true)
public class WebRtcMediaHandlerServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	private static final Logger log = LoggerFactory
			.getLogger(WebRtcMediaHandlerServlet.class);

	@Autowired
	private WebRtcMediaHandler webRtcMediaHandler;
	@Autowired
	private WebRtcControlProtocolManager protocolManager;
	@Autowired
	private ContentApiExecutorService executor;

	private WebRtcMediaRequestManager webRtcMediaRequestManager;

	@Override
	public void init() throws ServletException {
		super.init();

		// Recover application context associated to this servlet in this
		// context
		AnnotationConfigApplicationContext thisServletContext = KurentoApplicationContextUtils
				.getKurentoServletApplicationContext(this.getClass(),
						this.getServletName());

		// If there is not application context associated to this servlet,
		// create one
		if (thisServletContext == null) {
			// Locate the handler class associated to this servlet
			String handlerClass = this
					.getInitParameter(ContentApiWebApplicationInitializer.WEB_RTC_MEDIA_HANDLER_CLASS_PARAM_NAME);
			if (handlerClass == null || handlerClass.equals("")) {
				String message = "Cannot find handler class associated to handler servlet with name "
						+ this.getServletConfig().getServletName()
						+ " and class " + this.getClass().getName();
				log.error(message);
				throw new ServletException(message);
			}
			// Create application context for this servlet containing the
			// handler
			thisServletContext = KurentoApplicationContextUtils
					.createKurentoServletApplicationContext(this.getClass(),
							this.getServletName(), this.getServletContext(),
							handlerClass);
		}

		// Make this servlet to receive beans to resolve the @Autowired present
		// on it
		KurentoApplicationContextUtils
				.processInjectionBasedOnApplicationContext(this,
						thisServletContext);

		webRtcMediaRequestManager = (WebRtcMediaRequestManager) KurentoApplicationContextUtils
				.getBean("webRtcMediaRequestManager");

	}

	@Override
	protected final void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		resp.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED,
				"Only POST request are supported for this service");
	}

	@Override
	protected final void doPost(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {

		log.debug("Request received: " + req.getRequestURI());

		if (!req.isAsyncSupported()) {
			// Async context could not be created. It is not necessary to
			// complete it. Just send error message to
			resp.sendError(
					HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"AsyncContext could not be started. The application should add \"asyncSupported = true\" in all "
							+ this.getClass().getName()
							+ " instances and in all filters in the associated chain");
			return;
		}
		if (webRtcMediaHandler == null) {
			resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"Application must implement a WebRtcMediaHanlder");
			return;
		}

		String contentId = req.getPathInfo();
		if (contentId != null) {
			contentId = contentId.substring(1);
		}

		AsyncContext asyncCtx = req.startAsync();

		// Add listener for managing error conditions
		asyncCtx.addListener(new ContentAsyncListener());

		WebRtcJsonRequest message = null;
		try {
			message = protocolManager.receiveJsonRequest(asyncCtx);
		} catch (JsonSyntaxException jse) {
			protocolManager.sendJsonError(asyncCtx, WebRtcJsonResponse
					.newError(
							ERROR_PARSE_ERROR,
							"Json syntax is not valid. Reason: "
									+ jse.getMessage(), 0));
			return;
		} catch (JsonIOException jie) {
			protocolManager.sendJsonError(asyncCtx, WebRtcJsonResponse
					.newError(
							ERROR_INTERNAL_ERROR,
							"Cloud not read Json string. Reason: "
									+ jie.getMessage(), 0));
			return;
		}

		if (message == null) {
			protocolManager
					.sendJsonError(asyncCtx, WebRtcJsonResponse.newError(
							ERROR_INTERNAL_ERROR,
							"Cannot process message with null action field", 0));
			return;
		}

		WebRtcMediaRequestImpl mediaRequest = null;
		if (message.getMethod().equals(METHOD_START)) {
			mediaRequest = webRtcMediaRequestManager.create(webRtcMediaHandler,
					contentId, req);
		} else if (message.getSessionId() != null) {
			mediaRequest = webRtcMediaRequestManager
					.get(message.getSessionId());
			if (mediaRequest == null) {
				protocolManager.sendJsonError(asyncCtx, WebRtcJsonResponse
						.newError(ERROR_INVALID_REQUEST,
								"Cloud not find WebRtcMedia state associated to sessionId "
										+ message.getSessionId(),
								message.getId()));
				return;
			}
		} else {
			protocolManager
					.sendJsonError(
							asyncCtx,
							WebRtcJsonResponse
									.newError(
											ERROR_INVALID_REQUEST,
											"Cloud not find required sessionId field in request",
											message.getId()));
			return;
		}

		Future<?> future = executor
				.getExecutor()
				.submit((AsyncWebRtcMediaRequestProcessor) KurentoApplicationContextUtils
						.getBean("asyncWebRtcMediaRequestProcessor",
								mediaRequest, message, asyncCtx));

		// Store future for using it in case of error
		req.setAttribute(ContentAsyncListener.FUTURE_REQUEST_ATT_NAME, future);
		req.setAttribute(ContentAsyncListener.WEBRTC_MEDIA_REQUEST_ATT_NAME,
				mediaRequest);
		req.setAttribute(ContentAsyncListener.WEBRTC_JSON_REQUEST_ATT_NAME,
				message);
	}
}