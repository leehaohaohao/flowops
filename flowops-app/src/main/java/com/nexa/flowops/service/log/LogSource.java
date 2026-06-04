package com.nexa.flowops.service.log;

import org.springframework.beans.factory.annotation.Value;


import java.util.List;

public interface LogSource {

    List<String> listFiles(Long serviceId, String type, String date);
    String readContent(Long serviceId, String type, String filename, long offset, long limit);
    List<String> listDates(Long serviceId, String type);
}
