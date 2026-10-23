CREATE TABLE `kindle_imports` (
	`id` integer PRIMARY KEY NOT NULL,
	`user_id` integer NOT NULL,
	`imported_at` text DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')) NOT NULL,
	`words_new` integer NOT NULL,
	`words_skipped` integer NOT NULL,
	`last_kindle_timestamp` integer,
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE TABLE `lookup_cache` (
	`cache_key` text PRIMARY KEY NOT NULL,
	`payload` text NOT NULL,
	`created_at` text DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')) NOT NULL
);
--> statement-breakpoint
CREATE TABLE `sessions` (
	`token` text PRIMARY KEY NOT NULL,
	`user_id` integer NOT NULL,
	`device` text,
	`created_at` text DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')) NOT NULL,
	`last_seen` text,
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE TABLE `users` (
	`id` integer PRIMARY KEY NOT NULL,
	`name` text NOT NULL,
	`pin_hash` text,
	`created_at` text DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')) NOT NULL
);
--> statement-breakpoint
CREATE UNIQUE INDEX `users_name_unique` ON `users` (`name`);--> statement-breakpoint
CREATE TABLE `words` (
	`id` integer PRIMARY KEY NOT NULL,
	`user_id` integer NOT NULL,
	`word` text NOT NULL,
	`definition` text NOT NULL,
	`example` text,
	`finnish` text,
	`ipa` text,
	`audio_path` text,
	`source` text DEFAULT 'claude' NOT NULL,
	`status` text DEFAULT 'learning' NOT NULL,
	`learned_at` text,
	`book_title` text,
	`book_author` text,
	`kindle_lookup_at` text,
	`notes` text,
	`created_at` text DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')) NOT NULL,
	`updated_at` text DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')) NOT NULL,
	`deleted_at` text,
	FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE INDEX `words_user_status` ON `words` (`user_id`,`status`);--> statement-breakpoint
CREATE INDEX `words_user_updated` ON `words` (`user_id`,`updated_at`);--> statement-breakpoint
CREATE UNIQUE INDEX `words_user_id_word_unique` ON `words` (`user_id`,`word`);