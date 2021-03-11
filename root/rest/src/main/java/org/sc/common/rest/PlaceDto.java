package org.sc.common.rest;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PlaceDto {
    private String id;
    private String name;
    private String description;
    private List<String> tags;
    private List<String> mediaIds;
    private TrailCoordinatesDto coordinates;
    private List<String> crossingTrailIds;
}
