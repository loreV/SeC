package org.sc.data.repository;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Aggregates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.sc.configuration.DataSource;
import org.sc.data.entity.mapper.*;
import org.sc.data.geo.CoordinatesRectangle;
import org.sc.data.model.*;
import org.sc.processor.TrailSimplifierLevel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.StreamSupport;

import static com.mongodb.client.model.Aggregates.match;
import static com.mongodb.client.model.Aggregates.project;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Projections.*;
import static java.util.stream.Collectors.toList;
import static org.sc.data.repository.MongoConstants.*;

@Repository
public class TrailDAO {

    public static final String PLACE_ID_IN_LOCATIONS = Trail.LOCATIONS + DOT + PlaceRef.PLACE_ID;
    public static final String NO_FILTERING = "*";
    public static final String REALM_STRUCT = Trail.FILE_DETAILS + DOT + FileDetails.REALM;


    private final MongoCollection<Document> collection;

    private final Mapper<Trail> trailMapper;
    private final SelectiveArgumentMapper<Trail> trailLevelMapper;
    private final Mapper<TrailPreview> trailPreviewMapper;
    private final LinkedMediaMapper linkedMediaMapper;
    private final PlaceRefMapper placeRefMapper;

    @Autowired
    public TrailDAO(final DataSource dataSource,
                    final TrailMapper trailMapper,
                    final SelectiveArgumentMapper<Trail> trailLevelMapper,
                    final LinkedMediaMapper linkedMediaMapper,
                    final TrailPreviewMapper trailPreviewMapper,
                    final PlaceRefMapper placeRefMapper) {
        this.collection = dataSource.getDB().getCollection(Trail.COLLECTION_NAME);
        this.trailMapper = trailMapper;
        this.trailLevelMapper = trailLevelMapper;
        this.linkedMediaMapper = linkedMediaMapper;
        this.trailPreviewMapper = trailPreviewMapper;
        this.placeRefMapper = placeRefMapper;
    }

    public List<Trail> getTrails(int skip, int limit,
                                 final TrailSimplifierLevel trailSimplifierLevel,
                                 final String realm) {
        final Document realmFilter = !realm.equals(NO_FILTERING) ? new Document() :
                new Document(Trail.FILE_DETAILS + DOT + FileDetails.REALM, realm);
        return toTrailsList(collection.find(realmFilter).skip(skip).limit(limit),
                TrailSimplifierLevel.LOW);
    }

    public List<Trail> getTrailById(final String id,
                                    final TrailSimplifierLevel trailSimplifierLevel) {
        //if level=
        //if (level.equals(TrailSimplifierLevel.LOW.toString())){
        //return toTrailsList(collection.find(new Document(Trail.ID, id)));
        // }
        //else if....
        //return toTrailsList(collection.find(new Document(Trail.ID, id)));
        return toTrailsList(collection.find(new Document(Trail.ID, id)), trailSimplifierLevel);
    }

    public List<Trail> getTrailByPlaceId(final String id,
                                         final int page,
                                         final int limit,
                                         final TrailSimplifierLevel trailSimplifierLevel) {
        return toTrailsList(collection.find(new Document(PLACE_ID_IN_LOCATIONS, id)).skip(page).limit(limit),
                trailSimplifierLevel);
    }

    public List<Trail> delete(final String id) {
        List<Trail> trailByCode = getTrailById(id, TrailSimplifierLevel.MEDIUM);
        collection.deleteOne(new Document(Trail.ID, id));
        return trailByCode;
    }

    public List<Trail> upsert(final Trail trailRequest) {
        final String existingOrNewObjectId = trailRequest.getId() == null ?
                new ObjectId().toHexString() : trailRequest.getId();
        final Document trailDocument = trailMapper.mapToDocument(trailRequest)
                .append(Trail.ID, existingOrNewObjectId);
        final Document updateResult = collection.findOneAndReplace(
                new Document(Trail.ID, existingOrNewObjectId),
                trailDocument, UPSERT_OPTIONS);
        if (updateResult == null) {
            throw new IllegalStateException();
        }
        return Collections.singletonList(trailMapper.mapToObject(updateResult));
    }

    public List<TrailPreview> getTrailPreviews(final int skip, final int limit, final String realm) {

        final Bson filter = realm.equals(NO_FILTERING_TOKEN) ? getNoFilter() : getRealmFilter(realm);
        final Bson project = getTrailPreviewProjection();

        final Bson aLimit = Aggregates.limit(limit);
        final Bson aSkip = Aggregates.skip(skip);

        return toTrailsPreviewList(collection.aggregate(Arrays.asList(filter, project, aLimit, aSkip)));
    }

    public List<TrailPreview> trailPreviewById(final String id) {
        final Bson project = getTrailPreviewProjection();
        final Bson equalId = match(eq(Trail.ID, id));
        return toTrailsPreviewList(collection.aggregate(Arrays.asList(equalId, project)));
    }

    public void unlinkMediaByAllTrails(final String mediaId) {
        // E.g: db.core.test.update({"b.mediaId": 1}, { $pull : { "b.$.mediaId": 1}}, {multi: true})
        collection.updateMany(new Document(),
                new Document($PULL, new Document((Trail.MEDIA),
                        new Document(LinkedMedia.ID, mediaId))));
    }

    public List<Trail> linkMedia(final String id,
                                 final LinkedMedia linkMedia) {
        collection.updateOne(new Document(Trail.ID, id),
                new Document(ADD_TO_SET, new Document(Trail.MEDIA,
                        linkedMediaMapper.mapToDocument(linkMedia))));
        return getTrailById(id, TrailSimplifierLevel.LOW);
    }

    public List<Trail> unlinkMedia(final String id,
                                   final String mediaId) {
        collection.updateOne(new Document(Trail.ID, id),
                new Document($PULL, new Document(Trail.MEDIA,
                        new Document(LinkedMedia.ID, mediaId))));
        return getTrailById(id, TrailSimplifierLevel.LOW);
    }

    public List<Trail> findTrailWithinGeoSquare(
            final CoordinatesRectangle geoSquare,
            final int skip, final int limit, final TrailSimplifierLevel level) {
        final List<Double> resolvedTopLeftVertex = Arrays.asList(geoSquare.getBottomLeft().getLongitude(),
                geoSquare.getTopRight().getLatitude());
        final List<Double> resolvedBottomRightVertex = Arrays.asList(geoSquare.getTopRight().getLongitude(),
                geoSquare.getBottomLeft().getLatitude());
        FindIterable<Document> foundTrails = collection.find(
                new Document(Trail.GEO_LINE,
                        new Document($_GEO_INTERSECT,
                                new Document($_GEOMETRY, new Document(GEO_TYPE, GEO_LINE_STRING)
                                        .append(GEO_COORDINATES,
                                                Arrays.asList(
                                                        geoSquare.getBottomLeft().getAsList(),
                                                        resolvedTopLeftVertex,
                                                        geoSquare.getTopRight().getAsList(),
                                                        resolvedBottomRightVertex)
                                        ))))).skip(skip).limit(limit);
        return toTrailsList(foundTrails, level);
    }

    public List<Trail> findTrailPerfectlyContainedInGeoSquare(
            CoordinatesRectangle outerGeoSquare,
            final int skip, final int limit, final TrailSimplifierLevel level) {
        FindIterable<Document> foundTrails = collection.find(new Document(Trail.GEO_LINE,
                new Document($_GEO_WITHIN, new Document(
                        $_BOX,
                        Arrays.asList(outerGeoSquare.getBottomLeft().getAsList(),
                                outerGeoSquare.getTopRight().getAsList())
                )))).skip(skip).limit(limit);
        return toTrailsList(foundTrails, level);
    }

    public List<Trail> linkPlace(String id, PlaceRef placeRef) {
        collection.updateOne(new Document(Trail.ID, id),
                new Document(ADD_TO_SET, new Document(Trail.LOCATIONS,
                        placeRefMapper.mapToDocument(placeRef))));
        return getTrailById(id, TrailSimplifierLevel.LOW);
    }

    public List<Trail> unLinkPlace(String id, PlaceRef placeRef) {
        collection.updateOne(new Document(Trail.ID, id),
                new Document($PULL, new Document(Trail.LOCATIONS,
                        new Document(PlaceRef.PLACE_ID, placeRef.getPlaceId()))));
        return getTrailById(id, TrailSimplifierLevel.LOW);
    }

    public void unlinkPlaceFromAllTrails(String placeId) {
        Document update = new Document($PULL, new Document(Trail.LOCATIONS,
                new Document(PlaceRef.PLACE_ID, placeId)));
        collection.updateMany(new Document(),
                update);
    }

    public long countTrail() {
        return collection.countDocuments();
    }

    private List<TrailPreview> toTrailsPreviewList(final AggregateIterable<Document> documents) {
        return StreamSupport.stream(documents.spliterator(), false)
                .map(trailPreviewMapper::mapToObject).collect(toList());
    }

    private List<Trail> toTrailsList(final Iterable<Document> documents,
                                     final TrailSimplifierLevel trailSimplifierLevel) {
        return StreamSupport.stream(documents.spliterator(), false)
                .map(t-> trailLevelMapper.mapToObject(t, trailSimplifierLevel)).collect(toList());
    }

    private Bson getRealmFilter(final String realm) {
        return match(new Document(REALM_STRUCT, realm));
    }

    private Bson getNoFilter() {
        return match(new Document(REALM_STRUCT, new Document($NOT_EQUAL, "")));
    }

    private Bson getTrailPreviewProjection() {
        return project(fields(
                include(Trail.CLASSIFICATION),
                include(Trail.CYCLO),
                include(Trail.STATUS),
                include(Trail.FILE_DETAILS),
                include(Trail.LAST_UPDATE_DATE),
                include(Trail.CODE),
                computed(Trail.START_POS,
                        new Document("$arrayElemAt",
                                Arrays.asList(DOLLAR + Trail.LOCATIONS, 0))),
                computed(Trail.FINAL_POS,
                        new Document("$arrayElemAt",
                                Arrays.asList(DOLLAR + Trail.LOCATIONS, -1)))
        ));
    }
}