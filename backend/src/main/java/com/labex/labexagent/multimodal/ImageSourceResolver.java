package com.labex.labexagent.multimodal;

@FunctionalInterface
public interface ImageSourceResolver {
    String resolve(String imageSource);
}
