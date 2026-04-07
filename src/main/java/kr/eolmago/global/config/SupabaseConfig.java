package kr.eolmago.global.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

@Getter
@Configuration
@ConditionalOnProperty(name = "supabase.url", matchIfMissing = false)
public class SupabaseConfig {

    @Value("${supabase.url}")
    private String url;

    @Value("${supabase.anon-key}")
    private String anonKey;

    @Value("${supabase.service-role-key}")
    private String serviceRoleKey;

    @Value("${supabase.bucket}")
    private String bucket;
}
