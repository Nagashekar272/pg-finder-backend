package com.pgfind.model;

import java.util.ArrayList;
import java.util.List;

public class Pg {
    private String id;
    private String name;
    private String city;
    private String area;
    private String address;
    private String pgType; // "Boys", "Girls", "Coliving"
    private List<String> sharingOptions = new ArrayList<>();
    private Double startingPrice;
    private List<String> amenities = new ArrayList<>();
    private String contactNumber;
    private String contactEmail;
    private Double rating;
    private String imageUrl;
    private String description;
    private String placeId; // Reference to Google Maps Place ID
    private Double latitude;
    private Double longitude;
    private Integer totalRatings;
    private List<String> photos = new ArrayList<>();
    private String website;
    private String businessStatus;
    private String openingStatus;
    private Boolean openNow;

    // Constructors
    public Pg() {
    }

    public Pg(String id, String name, String city, String area, String address, String pgType,
              List<String> sharingOptions, Double startingPrice, List<String> amenities,
              String contactNumber, String contactEmail, Double rating, String imageUrl, String description) {
        this.id = id;
        this.name = name;
        this.city = city;
        this.area = area;
        this.address = address;
        this.pgType = pgType;
        this.sharingOptions = sharingOptions;
        this.startingPrice = startingPrice;
        this.amenities = amenities;
        this.contactNumber = contactNumber;
        this.contactEmail = contactEmail;
        this.rating = rating;
        this.imageUrl = imageUrl;
        this.description = description;
    }

    public Pg(String id, String name, String city, String area, String address, String pgType,
              List<String> sharingOptions, Double startingPrice, List<String> amenities,
              String contactNumber, String contactEmail, Double rating, String imageUrl, String description, String placeId) {
        this(id, name, city, area, address, pgType, sharingOptions, startingPrice, amenities, contactNumber, contactEmail, rating, imageUrl, description);
        this.placeId = placeId;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getArea() {
        return area;
    }

    public void setArea(String area) {
        this.area = area;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getPgType() {
        return pgType;
    }

    public void setPgType(String pgType) {
        this.pgType = pgType;
    }

    public List<String> getSharingOptions() {
        return sharingOptions;
    }

    public void setSharingOptions(List<String> sharingOptions) {
        this.sharingOptions = sharingOptions;
    }

    public Double getStartingPrice() {
        return startingPrice;
    }

    public void setStartingPrice(Double startingPrice) {
        this.startingPrice = startingPrice;
    }

    public List<String> getAmenities() {
        return amenities;
    }

    public void setAmenities(List<String> amenities) {
        this.amenities = amenities;
    }

    public String getContactNumber() {
        return contactNumber;
    }

    public void setContactNumber(String contactNumber) {
        this.contactNumber = contactNumber;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    public Double getRating() {
        return rating;
    }

    public void setRating(Double rating) {
        this.rating = rating;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPlaceId() {
        return placeId;
    }

    public void setPlaceId(String placeId) {
        this.placeId = placeId;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public Integer getTotalRatings() {
        return totalRatings;
    }

    public void setTotalRatings(Integer totalRatings) {
        this.totalRatings = totalRatings;
    }

    public List<String> getPhotos() {
        return photos;
    }

    public void setPhotos(List<String> photos) {
        this.photos = photos;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public String getBusinessStatus() {
        return businessStatus;
    }

    public void setBusinessStatus(String businessStatus) {
        this.businessStatus = businessStatus;
    }

    public String getOpeningStatus() {
        return openingStatus;
    }

    public void setOpeningStatus(String openingStatus) {
        this.openingStatus = openingStatus;
    }

    public Boolean getOpenNow() {
        return openNow;
    }

    public void setOpenNow(Boolean openNow) {
        this.openNow = openNow;
    }

    @Override
    public String toString() {
        return "Pg{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", city='" + city + '\'' +
                ", area='" + area + '\'' +
                ", address='" + address + '\'' +
                ", pgType='" + pgType + '\'' +
                ", startingPrice=" + startingPrice +
                ", contactNumber='" + contactNumber + '\'' +
                ", rating=" + rating +
                ", placeId='" + placeId + '\'' +
                ", latitude=" + latitude +
                ", longitude=" + longitude +
                ", totalRatings=" + totalRatings +
                ", website='" + website + '\'' +
                ", businessStatus='" + businessStatus + '\'' +
                ", openingStatus='" + openingStatus + '\'' +
                ", openNow=" + openNow +
                '}';
    }
}
