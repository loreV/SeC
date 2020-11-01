package org.sc.controller;

import com.google.inject.Inject;
import org.sc.data.AccessibilityNotification;
import org.sc.data.AccessibilityNotificationDAO;
import org.sc.data.helper.GsonBeanHelper;
import org.sc.data.helper.JsonHelper;
import org.sc.importer.AccessibilityCreationValidator;
import spark.Request;
import spark.Response;
import spark.Spark;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static java.lang.String.format;
import static org.sc.configuration.ConfigurationProperties.API_PREFIX;
import static org.sc.controller.TrailController.BAD_REQUEST_STATUS_CODE;

public class AccessibilityNotificationController implements PublicController {

    private final static String PREFIX = API_PREFIX + "/accessibility";

    private final GsonBeanHelper gsonBeanHelper;
    private final AccessibilityCreationValidator accessibilityValidator;
    private final AccessibilityNotificationDAO accessibilityDAO;


    @Inject
    public AccessibilityNotificationController(final GsonBeanHelper gsonBeanHelper,
                                               final AccessibilityNotificationDAO accessibilityDao,
                                               final AccessibilityCreationValidator accessibilityValidator) {
        this.accessibilityDAO = accessibilityDao;
        this.gsonBeanHelper = gsonBeanHelper;
        this.accessibilityValidator = accessibilityValidator;
    }

    private RESTResponse getSolved(Request request, Response response) {
        return new AccessibilityResponse(accessibilityDAO.getSolved());
    }

    private RESTResponse getNotSolved(Request request, Response response) {
        return new AccessibilityResponse(accessibilityDAO.getNotSolved());
    }

    private RESTResponse getByTrailCode(Request request, Response response) {
        return new AccessibilityResponse(accessibilityDAO.getByCode(request.params(":code")));
    }

    private RESTResponse getUnresolvedByTrailCode(Request request, Response response) {
        return new AccessibilityResponse(accessibilityDAO.getByCodeNotResolved(request.params(":code")));
    }

    private RESTResponse deleteAccessibilityNotification(Request request, Response response) {
        final String requestId = request.params(":id");
        boolean isDeleted = accessibilityDAO.delete(requestId);
        if (isDeleted) {
            return new RESTResponse(Status.OK, Collections.emptySet());
        } else {
            return new RESTResponse(Status.ERROR,
                    new HashSet<>(Collections.singletonList(
                            format("No accessibility notification was found with id '%s'", requestId))));
        }
    }

    private RESTResponse createMaintenance(Request request, Response response) {
        AccessibilityNotification maintenance = convertRequestToAccessibilityNotification(request);
        final Set<String> errors = accessibilityValidator.validate(request);
        if(errors.isEmpty()) {
            accessibilityDAO.upsert(maintenance);
            return new RESTResponse();
        }
        response.status(BAD_REQUEST_STATUS_CODE);
        return new RESTResponse(errors);
    }

    public void init() {
        Spark.get(format("%s/solved", PREFIX), this::getSolved, JsonHelper.json());
        Spark.get(format("%s/unsolved", PREFIX), this::getNotSolved, JsonHelper.json());
        Spark.get(format("%s/unsolved/:code", PREFIX), this::getUnresolvedByTrailCode, JsonHelper.json());
        Spark.get(format("%s/code/:code", PREFIX), this::getByTrailCode, JsonHelper.json());
        Spark.delete(format("%s/delete/:id", PREFIX), this::deleteAccessibilityNotification, JsonHelper.json());
        Spark.put(format("%s/save", PREFIX), this::createMaintenance, JsonHelper.json());
    }

    private AccessibilityNotification convertRequestToAccessibilityNotification(final Request request) {
        final String requestBody = request.body();
        return Objects.requireNonNull(gsonBeanHelper.getGsonBuilder())
                .fromJson(requestBody, AccessibilityNotification.class);
    }
}
