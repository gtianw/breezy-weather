package wangdaye.com.geometricweather.main;

import android.app.Application;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import wangdaye.com.geometricweather.basic.GeoViewModel;
import wangdaye.com.geometricweather.basic.models.Location;
import wangdaye.com.geometricweather.basic.models.resources.Resource;
import wangdaye.com.geometricweather.main.models.Indicator;
import wangdaye.com.geometricweather.main.models.LocationResource;
import wangdaye.com.geometricweather.main.models.PermissionsRequest;
import wangdaye.com.geometricweather.main.models.SelectableLocationListResource;
import wangdaye.com.geometricweather.main.utils.MainModuleUtils;

public class MainActivityViewModel extends GeoViewModel
        implements MainActivityRepository.WeatherRequestCallback {

    private final MutableLiveData<LocationResource> mCurrentLocation;
    private final MutableLiveData<SelectableLocationListResource> mListResource;
    private final MutableLiveData<Indicator> mIndicator;
    private final MutableLiveData<PermissionsRequest> mPermissionsRequest;

    private final SavedStateHandle mSavedStateHandle;
    private final MainActivityRepository mRepository;

    // inner data.
    private @Nullable String mFormattedId; // current formatted id.
    private @Nullable List<Location> mTotalList; // all locations.
    private @Nullable List<Location> mValidList; // location list optimized for resident city.

    private Status mStatus;
    private enum Status {
        INITIALIZING, IMPLICIT_INITIALIZING, IDLE
    }

    private static final String KEY_FORMATTED_ID = "formatted_id";

    public MainActivityViewModel(@NonNull Application application, SavedStateHandle handle) {
        this(application, handle, new MainActivityRepository(application));
    }

    public MainActivityViewModel(@NonNull Application application, SavedStateHandle handle,
                                 MainActivityRepository repository) {
        super(application);

        mCurrentLocation = new MutableLiveData<>();
        mCurrentLocation.setValue(null);

        mListResource = new MutableLiveData<>();
        mListResource.setValue(new SelectableLocationListResource(
                new ArrayList<>(), null, null));

        mIndicator = new MutableLiveData<>();
        mIndicator.setValue(new Indicator(1, 0));

        mPermissionsRequest = new MutableLiveData<>();
        mPermissionsRequest.setValue(
                new PermissionsRequest(new ArrayList<>(), null, false));

        mSavedStateHandle = handle;
        mRepository = repository;

        mFormattedId = mSavedStateHandle.get(KEY_FORMATTED_ID);
        mTotalList = null;
        mValidList = null;

        mStatus = Status.IDLE;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        mRepository.destroy();
    }

    public void init() {
        init(mFormattedId);
    }

    public void init(@Nullable String formattedId) {
        setFormattedId(formattedId);

        List<Location> oldList = mTotalList == null
                ? new ArrayList<>()
                : Collections.unmodifiableList(mTotalList);

        mStatus = Status.INITIALIZING;
        mRepository.getLocationList(getApplication(), oldList, (locationList, done) -> {
            if (done) {
                mStatus = Status.IDLE;
            }

            if (locationList == null) {
                return;
            }

            List<Location> totalList = new ArrayList<>(locationList);
            List<Location> validList = Location.excludeInvalidResidentLocation(getApplication(), totalList);
            int validIndex = indexLocation(validList, mFormattedId);

            setInnerData(totalList, validList, validIndex);

            Location current = validList.get(validIndex);
            Indicator indicator = new Indicator(validList.size(), validIndex);
            boolean defaultLocation = validIndex == 0;

            setLocationResourceWithVerification(current, defaultLocation, false,
                    indicator, null, new SelectableLocationListResource.DataSetChanged());
        });
    }

    public void updateLocationFromBackground(Location location) {
        mStatus = Status.IMPLICIT_INITIALIZING;
        mRepository.readWeatherCache(getApplication(), location, (weather, done) -> {
            if (done) {
                mStatus = Status.IDLE;
            }

            if (mTotalList == null || mValidList == null) {
                return;
            }

            location.setWeather(weather);

            List<Location> totalList = new ArrayList<>(mTotalList);
            for (int i = 0; i < totalList.size(); i ++) {
                if (totalList.get(i).equals(location)) {
                    totalList.set(i, location);
                    break;
                }
            }

            List<Location> validList = Location.excludeInvalidResidentLocation(getApplication(), totalList);

            int validIndex = indexLocation(validList, mFormattedId);

            setInnerData(totalList, validList, validIndex);

            Location current = validList.get(validIndex);
            Indicator indicator = new Indicator(validList.size(), validIndex);
            boolean defaultLocation = validIndex == 0;

            setLocationResourceWithVerification(current, defaultLocation, true,
                    indicator, null, new SelectableLocationListResource.DataSetChanged());
        });
    }

    public void setLocation(@NonNull String formattedId) {
        if (mTotalList == null || mValidList == null) {
            return;
        }

        List<Location> totalList = new ArrayList<>(mTotalList);
        List<Location> validList = new ArrayList<>(mValidList);
        int validIndex = indexLocation(validList, formattedId);

        setInnerData(totalList, validList, validIndex);

        Location current = validList.get(validIndex);
        Indicator indicator = new Indicator(validList.size(), validIndex);
        boolean defaultLocation = validIndex == 0;

        setLocationResourceWithVerification(current, defaultLocation, false,
                indicator, null, new SelectableLocationListResource.DataSetChanged());
    }

    public void setLocation(int offset) {
        if (mTotalList == null || mValidList == null) {
            return;
        }

        int validIndex = indexLocation(mValidList, mFormattedId);
        validIndex += offset + mValidList.size();
        validIndex %= mValidList.size();

        List<Location> totalList = new ArrayList<>(mTotalList);
        List<Location> validList = new ArrayList<>(mValidList);

        setInnerData(totalList, validList, validIndex);

        Location current = validList.get(validIndex);
        Indicator indicator = new Indicator(validList.size(), validIndex);
        boolean defaultLocation = validIndex == 0;

        setLocationResourceWithVerification(current, defaultLocation, false,
                indicator, null, new SelectableLocationListResource.DataSetChanged());
    }

    public void updateWeather(boolean triggeredByUser, boolean checkPermissions) {
        if (mCurrentLocation.getValue() == null) {
            return;
        }

        mRepository.cancelWeatherRequest();

        Location location = mCurrentLocation.getValue().data;

        mCurrentLocation.setValue(
                LocationResource.loading(
                        location,
                        mCurrentLocation.getValue().defaultLocation,
                        false
                )
        );

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || !location.isCurrentPosition()
                || !checkPermissions) {
            // don't need to request any permission -> request data directly.
            mRepository.getWeather(
                    getApplication(), location, location.isCurrentPosition(), this);
            return;
        }

        // check permissions.
        List<String> permissionList = getDeniedPermissionList();
        if (permissionList.size() == 0) {
            // already got all permissions -> request data directly.
            mRepository.getWeather(
                    getApplication(), location, true, this);
            return;
        }

        // request permissions.
        mPermissionsRequest.setValue(new PermissionsRequest(permissionList, location, triggeredByUser));
    }

    public void requestPermissionsFailed(Location location) {
        if (mTotalList == null || mValidList == null) {
            return;
        }

        mCurrentLocation.setValue(
                LocationResource.error(
                        location,
                        location.equals(mValidList.get(0)),
                        false
                )
        );
    }

    public void addLocation(Location location) {
        if (mStatus == Status.INITIALIZING) {
            return;
        }

        addLocation(location, mTotalList.size());
    }

    public void addLocation(Location location, int position) {
        if (mStatus == Status.INITIALIZING) {
            return;
        }

        List<Location> totalList = new ArrayList<>(mTotalList);
        totalList.add(position, location);

        List<Location> validList = Location.excludeInvalidResidentLocation(getApplication(), totalList);
        int validIndex = indexLocation(mValidList, mFormattedId);

        setInnerData(totalList, validList, validIndex);

        Location current = validList.get(validIndex);
        Indicator indicator = new Indicator(validList.size(), validIndex);
        boolean defaultLocation = validIndex == 0;

        setLocationResourceWithVerification(current, defaultLocation, false,
                indicator, null, new SelectableLocationListResource.DataSetChanged());

        if (position == mTotalList.size() - 1) {
            mRepository.writeLocation(getApplication(), location);
        } else {
            mRepository.writeLocationList(getApplication(), Collections.unmodifiableList(totalList), position);
        }
    }

    public void moveLocation(int from, int to) {
        if (mStatus == Status.INITIALIZING) {
            return;
        }

        List<Location> totalList = new ArrayList<>(mTotalList);
        Collections.swap(totalList, from, to);

        List<Location> validList = Location.excludeInvalidResidentLocation(getApplication(), totalList);
        int validIndex = indexLocation(mValidList, mFormattedId);

        setInnerData(totalList, validList, validIndex);

        Location current = validList.get(validIndex);
        Indicator indicator = new Indicator(validList.size(), validIndex);
        boolean defaultLocation = validIndex == 0;

        setLocationResourceWithVerification(current, defaultLocation, false,
                indicator, null, new SelectableLocationListResource.ItemMoved(from, to));
    }

    public void moveLocationFinish() {
        if (mStatus == Status.INITIALIZING) {
            return;
        }

        mRepository.writeLocationList(getApplication(), mTotalList);
    }

    public void forceUpdateLocation(Location location) {
        if (mStatus == Status.INITIALIZING) {
            return;
        }

        List<Location> totalList = new ArrayList<>(mTotalList);
        for (int i = 0; i < totalList.size(); i ++) {
            if (totalList.get(i).equals(location)) {
                totalList.set(i, location);
                break;
            }
        }

        List<Location> validList = Location.excludeInvalidResidentLocation(getApplication(), totalList);
        int validIndex = indexLocation(mValidList, mFormattedId);

        setInnerData(totalList, validList, validIndex);

        Location current = validList.get(validIndex);
        Indicator indicator = new Indicator(validList.size(), validIndex);
        boolean defaultLocation = validIndex == 0;

        setLocationResourceWithVerification(current, defaultLocation, false,
                indicator, location.getFormattedId(), new SelectableLocationListResource.DataSetChanged());

        mRepository.writeLocation(getApplication(), location);
    }

    public void forceUpdateLocation(Location location, int position) {
        if (mStatus == Status.INITIALIZING) {
            return;
        }

        List<Location> totalList = new ArrayList<>(mTotalList);
        totalList.set(position, location);

        List<Location> validList = Location.excludeInvalidResidentLocation(getApplication(), totalList);
        int validIndex = indexLocation(mValidList, mFormattedId);

        setInnerData(totalList, validList, validIndex);

        Location current = validList.get(validIndex);
        Indicator indicator = new Indicator(validList.size(), validIndex);
        boolean defaultLocation = validIndex == 0;

        setLocationResourceWithVerification(current, defaultLocation, false,
                indicator, location.getFormattedId(), new SelectableLocationListResource.DataSetChanged());

        mRepository.writeLocation(getApplication(), location);
    }

    @Nullable
    public Location deleteLocation(int position) {
        if (mStatus == Status.INITIALIZING) {
            return null;
        }

        List<Location> totalList = new ArrayList<>(mTotalList);
        Location location = totalList.remove(position);
        if (location.getFormattedId().equals(mFormattedId)) {
            setFormattedId(totalList.get(0).getFormattedId());
        }

        List<Location> validList = Location.excludeInvalidResidentLocation(getApplication(), totalList);
        int validIndex = indexLocation(mValidList, mFormattedId);

        setInnerData(totalList, validList, validIndex);

        Location current = validList.get(validIndex);
        Indicator indicator = new Indicator(validList.size(), validIndex);
        boolean defaultLocation = validIndex == 0;

        setLocationResourceWithVerification(current, defaultLocation, false,
                indicator, location.getFormattedId(), new SelectableLocationListResource.DataSetChanged());

        mRepository.deleteLocation(getApplication(), location);

        return location;
    }

    private void setFormattedId(@Nullable String formattedId) {
        mFormattedId = formattedId;
        mSavedStateHandle.set(KEY_FORMATTED_ID, formattedId);
    }

    private void setInnerData(@NonNull List<Location> totalList, @NonNull List<Location> validList,
                              int validIndex) {
        mTotalList = totalList;
        mValidList = validList;
        setFormattedId(validList.get(validIndex).getFormattedId());
    }

    private void setLocationResourceWithVerification(Location location,
                                                     boolean defaultLocation,
                                                     boolean fromBackgroundUpdate,
                                                     Indicator indicator,
                                                     @Nullable String forceUpdateId,
                                                     @NonNull SelectableLocationListResource.Source source) {
        switch (mStatus) {
            case INITIALIZING:
                mCurrentLocation.setValue(
                        LocationResource.loading(location, defaultLocation, fromBackgroundUpdate));
                break;

            case IMPLICIT_INITIALIZING:
                LocationResource resource = mCurrentLocation.getValue();
                Resource.Status status = resource == null ? Resource.Status.SUCCESS : resource.status;
                mCurrentLocation.setValue(
                        new LocationResource(location, status, defaultLocation, false, fromBackgroundUpdate));
                break;

            case IDLE:
                if (MainModuleUtils.needUpdate(getApplication(), location)) {
                    mCurrentLocation.setValue(
                            LocationResource.loading(location, defaultLocation, fromBackgroundUpdate));
                    updateWeather(false, true);
                } else {
                    mCurrentLocation.setValue(
                            LocationResource.success(location, defaultLocation, fromBackgroundUpdate));
                }
                break;
        }

        mListResource.setValue(new SelectableLocationListResource(
                new ArrayList<>(mTotalList), location.getFormattedId(), forceUpdateId, source));
        mIndicator.setValue(indicator);
    }

    private static int indexLocation(List<Location> locationList, @Nullable String formattedId) {
        if (TextUtils.isEmpty(formattedId)) {
            return 0;
        }

        for (int i = 0; i < locationList.size(); i ++) {
            if (locationList.get(i).equals(formattedId)) {
                return i;
            }
        }

        return 0;
    }

    private List<String> getDeniedPermissionList() {
        List<String> permissionList = mRepository.getLocatePermissionList();
        for (int i = permissionList.size() - 1; i >= 0; i --) {
            if (ActivityCompat.checkSelfPermission(getApplication(), permissionList.get(i))
                    == PackageManager.PERMISSION_GRANTED) {
                permissionList.remove(i);
            }
        }
        return permissionList;
    }

    @Nullable
    public Location getLocationFromList(int offset) {
        if (mTotalList == null || mValidList == null) {
            return null;
        }

        int validIndex = indexLocation(mValidList, mFormattedId);
        return mValidList.get(
                (validIndex + offset + mValidList.size()) % mValidList.size()
        );
    }

    public MutableLiveData<LocationResource> getCurrentLocation() {
        return mCurrentLocation;
    }

    public MutableLiveData<SelectableLocationListResource> getListResource() {
        return mListResource;
    }

    public MutableLiveData<Indicator> getIndicator() {
        return mIndicator;
    }

    public MutableLiveData<PermissionsRequest> getPermissionsRequest() {
        return mPermissionsRequest;
    }

    @Nullable
    public Location getCurrentLocationValue() {
        LocationResource resource = mCurrentLocation.getValue();
        if (resource != null) {
            return resource.data;
        } else {
            return null;
        }
    }

    @Nullable
    public String getCurrentFormattedId() {
        Location location = getCurrentLocationValue();
        if (location != null) {
            return location.getFormattedId();
        } else if (mFormattedId != null) {
            return mFormattedId;
        } else if (mValidList != null) {
            return mValidList.get(0).getFormattedId();
        } else {
            return null;
        }
    }

    public @Nullable List<Location> getTotalLocationList() {
        if (mTotalList != null) {
            return Collections.unmodifiableList(mTotalList);
        } else {
            return null;
        }
    }

    public @Nullable List<Location> getValidLocationList() {
        if (mValidList != null) {
            return Collections.unmodifiableList(mValidList);
        } else {
            return null;
        }
    }

    public PermissionsRequest getPermissionsRequestValue() {
        return mPermissionsRequest.getValue();
    }

    public boolean isInitializeDone() {
        return mStatus == Status.IDLE;
    }

    // weather request callback.

    @Override
    public void onLocationCompleted(Location location, boolean succeed, boolean done) {
        callback(location, !succeed, succeed, done);
    }

    @Override
    public void onGetWeatherCompleted(Location location, boolean succeed, boolean done) {
        callback(location, false, succeed, done);
    }

    private void callback(Location location,
                          boolean locateFailed, boolean succeed, boolean done) {
        if (mTotalList == null || mValidList == null) {
            return;
        }

        List<Location> totalList = new ArrayList<>(mTotalList);
        for (int i = 0; i < totalList.size(); i ++) {
            if (totalList.get(i).equals(location)) {
                totalList.set(i, location);
                break;
            }
        }

        List<Location> validList = Location.excludeInvalidResidentLocation(getApplication(), totalList);
        int validIndex = indexLocation(validList, getCurrentFormattedId());
        boolean defaultLocation = location.equals(validList.get(0));

        setInnerData(totalList, validList, validIndex);

        LocationResource resource;
        if (!done) {
            resource = LocationResource.loading(
                    location, defaultLocation, locateFailed, false);
        } else if (succeed) {
            resource = LocationResource.success(
                    location, defaultLocation, false);
        } else {
            resource = LocationResource.error(
                    location, defaultLocation, locateFailed, false);
        }

        Indicator indicator = new Indicator(validList.size(), validIndex);

        mCurrentLocation.setValue(resource);
        mIndicator.setValue(indicator);
    }
}