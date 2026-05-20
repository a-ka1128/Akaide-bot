package com.akaide.bot.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.*;

@Entity
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class TargetChannel {
    @Id
    private String channelId;
    private String channelName;
}